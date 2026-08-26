package pl.jclab.refio.core.services

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.db.Task
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolResult
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the OFF switch on the streaming execution path.
 *
 * WHY this matters: switching a tool OFF is the user's hard stop. The plain path honoured it, but
 * the streaming path (the one every editor call takes whenever a UI listener is attached, i.e. in
 * the IDE and in the TUI) only checked the coarse read-only/write mode. Turning
 * advance_code_editing OFF mid-turn therefore stopped nothing.
 */
class ToolExecutorStreamingPermissionTest {

    private val toolRegistry = mockk<ToolRegistry>()
    private val taskRepository = mockk<TaskRepository>()
    private val permissionsService = mockk<ToolPermissionsService>()
    private val editor = mockk<Tool>(relaxed = true)

    private val subtask = mockk<Subtask>(relaxed = true).also {
        every { it.id } returns "s1"
        every { it.taskId } returns "t1"
        every { it.orderIndex } returns 0
    }

    private lateinit var executor: ToolExecutor

    @BeforeEach
    fun setup() {
        every { editor.name } returns "advance_code_editing"
        every { editor.mode } returns ToolMode.WRITE
        coEvery { editor.execute(any()) } returns ToolResult(success = true, output = "written")
        every { toolRegistry.getTool("advance_code_editing") } returns editor
        every { taskRepository.findById("t1") } returns mockk<Task>(relaxed = true).also {
            every { it.mode } returns TaskMode.AGENT
        }
        executor = ToolExecutor(
            toolRegistry = toolRegistry,
            taskRepository = taskRepository,
            toolPermissionsService = permissionsService
        )
    }

    private val call = ToolCall(name = "advance_code_editing", params = mapOf("path" to "a.kt"))

    @Test
    fun `a tool switched OFF does not run on the streaming path`() = runTest {
        every { permissionsService.getPermission("advance_code_editing", TaskMode.AGENT, "t1") } returns
            PermissionLevel.OFF

        val result = executor.executeToolsWithStreaming(listOf(call), subtask, listener = null)

        assertFalse(result.success, "a disabled tool must not report a successful execution")
        coVerify(exactly = 0) { editor.execute(any()) }
    }

    @Test
    fun `an allowed tool still runs on the streaming path`() = runTest {
        every { permissionsService.getPermission("advance_code_editing", TaskMode.AGENT, "t1") } returns
            PermissionLevel.ON

        val result = executor.executeToolsWithStreaming(listOf(call), subtask, listener = null)

        assertTrue(result.success, "the OFF guard must not block permitted tools")
        coVerify(exactly = 1) { editor.execute(any()) }
    }
}
