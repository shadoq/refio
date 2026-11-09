package pl.jclab.refio.core.services.turn

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.services.SnapshotService
import pl.jclab.refio.core.services.ToolExecutor
import pl.jclab.refio.core.services.ToolResultSummarizer
import pl.jclab.refio.core.services.TurnLoopConfig
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolResult
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A subagent that failed half-way has still changed files and still says how far it got. Its
 * caller's history and the run document have to keep that, not only the error text.
 */
class TurnToolExecutorFailedResultTest {

    private val toolExecutor = mockk<ToolExecutor>()
    private val toolRegistry = mockk<ToolRegistry>()
    private lateinit var executor: TurnToolExecutor
    private lateinit var config: TurnLoopConfig

    @BeforeEach
    fun setup() {
        executor = TurnToolExecutor(
            toolExecutor = toolExecutor,
            toolRegistry = toolRegistry,
            subtaskRepository = mockk<SubtaskRepository>(relaxed = true),
            toolResultSummarizer = mockk<ToolResultSummarizer>(relaxed = true),
            snapshotService = mockk<SnapshotService>(relaxed = true)
        )
        config = mockk(relaxed = true)
        every { config.enableSnapshots } returns false
        every { config.toolTimeout } returns Duration.ofSeconds(60)
        every { config.networkToolTimeout } returns Duration.ofSeconds(60)
        every { toolRegistry.toSubtaskKind(any()) } returns SubtaskKind.PLAN_STEP
        every { toolRegistry.getTool(any()) } returns mockk<Tool>(relaxed = true).also {
            every { it.mode } returns ToolMode.READ_ONLY
        }
    }

    @Test
    fun `a failed subagent call keeps its files and report details`() = runBlocking {
        coEvery { toolExecutor.executeTool(any(), any()) } returns ToolResult(
            success = false,
            error = "[subagent coder · status: failed · stop: exception · iterations: 3 · tokens: 400/90 · time: 0:31]",
            filesChanged = listOf("src/a.js"),
            metadata = mapOf("subagent_name" to "coder", "status" to "failed", "files_changed" to listOf("src/a.js"))
        )

        val result = executor.executeSingleTool(
            taskId = "t1",
            toolCall = ToolCallData(id = "c1", name = "invoke_subagent", arguments = """{"subagent_name":"coder","goal":"implement c"}"""),
            subtaskId = "s1",
            listener = null, iteration = 1, _config = config,
            mode = TaskMode.AGENT, executionMode = ExecutionMode.AUTO,
            runId = "r1", depth = 0, profileOverrides = null, _subtaskIds = emptyMap()
        )

        assertFalse(result.success)
        assertEquals(listOf("src/a.js"), result.filesChanged)
        assertTrue(result.metadata.orEmpty().contains("\"status\":\"failed\""), "metadata: ${result.metadata}")
    }
}
