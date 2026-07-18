package pl.jclab.refio.core.services.turn

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.services.PermissionLevel
import pl.jclab.refio.core.services.SnapshotService
import pl.jclab.refio.core.services.ToolExecutor
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.services.ToolResultSummarizer
import pl.jclab.refio.core.services.ToolResultSummary
import pl.jclab.refio.core.services.TurnLoopConfig
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolResult
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Locks the rollback safety net: every write must be snapshotted before it runs.
 *
 * WHY this matters: the agent advertises "snapshot before every write" so an edit can be rolled
 * back. Streaming editors (advance_code_editing / multi_line_editor) snapshot inside
 * ToolExecutor.executeToolsWithStreaming, but a non-streaming write (write_file, multi_edit, an
 * overwrite) reaches toolExecutor.executeTool, which never snapshots. A single such write per turn
 * (the sequential path) therefore left no restore point - this test pins that gap closed.
 */
class TurnToolExecutorSnapshotTest {

    private val toolExecutor = mockk<ToolExecutor>()
    private val toolRegistry = mockk<ToolRegistry>()
    private val subtaskRepository = mockk<SubtaskRepository>(relaxed = true)
    private val toolResultSummarizer = mockk<ToolResultSummarizer>()
    private val permissionsService = mockk<ToolPermissionsService>()
    private val snapshotService = mockk<SnapshotService>(relaxed = true)

    private lateinit var executor: TurnToolExecutor
    private lateinit var config: TurnLoopConfig

    @BeforeEach
    fun setup() {
        executor = TurnToolExecutor(
            toolExecutor = toolExecutor,
            toolRegistry = toolRegistry,
            subtaskRepository = subtaskRepository,
            toolResultSummarizer = toolResultSummarizer,
            snapshotService = snapshotService,
            permissionsService = permissionsService
        )
        config = mockk(relaxed = true)
        every { config.enableSnapshots } returns true
        every { toolRegistry.toSubtaskKind(any()) } returns SubtaskKind.PLAN_STEP
        coEvery { toolResultSummarizer.summarizeToolResult(any(), any(), any(), any()) } returns
            ToolResultSummary("summary", wasSummarized = false, 0, 0, 0.0)
        coEvery { toolExecutor.executeTool(any(), any()) } returns ToolResult(success = true, output = "ok")
    }

    private fun tool(name: String, mode: ToolMode) = mockk<Tool>(relaxed = true).also {
        every { it.mode } returns mode
        every { it.name } returns name
    }

    private suspend fun execute(toolName: String, args: String) = executor.executeSingleTool(
        taskId = "t1",
        toolCall = ToolCallData(id = "c1", name = toolName, arguments = args),
        subtaskId = "s1",
        listener = null, iteration = 1, _config = config,
        mode = TaskMode.AGENT, executionMode = ExecutionMode.AUTO,
        runId = "r1", depth = 0, profileOverrides = null, _subtaskIds = emptyMap()
    )

    @Test
    fun `a single non-streaming write is snapshotted before execution`() = runTest {
        // The dominant single-write turn (sequential path) must snapshot the target file before the
        // edit, else rollback is impossible. Pre-fix this fails: executeTool (the non-streaming path)
        // does not snapshot, and only the streaming-editor path and parallel mixed-batch branch did.
        every { toolRegistry.getTool("write_file") } returns tool("write_file", ToolMode.WRITE)
        every { permissionsService.getPermission("write_file", any()) } returns PermissionLevel.ON

        execute("write_file", """{"path":"game.html","content":"x"}""")

        verify(exactly = 1) { snapshotService.createSnapshot("t1", "s1", listOf("game.html")) }
    }

    @Test
    fun `a read tool is never snapshotted`() = runTest {
        every { toolRegistry.getTool("read_file") } returns tool("read_file", ToolMode.READ_ONLY)
        every { permissionsService.getPermission("read_file", any()) } returns PermissionLevel.ON

        execute("read_file", """{"path":"game.html"}""")

        verify(exactly = 0) { snapshotService.createSnapshot(any(), any(), any()) }
    }

    @Test
    fun `success is structural - a succeeding tool whose output starts with Error is still success`() = runTest {
        // Regression: the turn loop inferred success from content.startsWith("Error:"), so a tool that
        // SUCCEEDS but returns output beginning with "Error:" (a log file being read, a grep hit on an
        // "Error:" line, a command whose stdout starts that way) was misclassified as a failure and
        // polluted the error-rate / consecutive-failure guards. ToolResultData.success now comes from
        // ToolResult.success, independent of the output text.
        every { toolRegistry.getTool("read_file") } returns tool("read_file", ToolMode.READ_ONLY)
        every { permissionsService.getPermission("read_file", any()) } returns PermissionLevel.ON
        coEvery { toolExecutor.executeTool(any(), any()) } returns
            ToolResult(success = true, output = "Error: connection refused — this is line 1 of the log being read")

        val result = execute("read_file", """{"path":"app.log"}""")

        assertTrue(result.success, "a succeeding tool must report success even when its output starts with Error:")
    }

    @Test
    fun `success is false when the tool genuinely fails`() = runTest {
        every { toolRegistry.getTool("read_file") } returns tool("read_file", ToolMode.READ_ONLY)
        every { permissionsService.getPermission("read_file", any()) } returns PermissionLevel.ON
        coEvery { toolExecutor.executeTool(any(), any()) } returns
            ToolResult(success = false, error = "file not found")

        val result = execute("read_file", """{"path":"missing.txt"}""")

        assertFalse(result.success, "a failing tool must report failure")
    }

    @Test
    fun `disabled snapshots skip the snapshot`() = runTest {
        every { config.enableSnapshots } returns false
        every { toolRegistry.getTool("write_file") } returns tool("write_file", ToolMode.WRITE)
        every { permissionsService.getPermission("write_file", any()) } returns PermissionLevel.ON

        execute("write_file", """{"path":"game.html","content":"x"}""")

        verify(exactly = 0) { snapshotService.createSnapshot(any(), any(), any()) }
    }
}
