package pl.jclab.refio.core.services

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.Task
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.tools.base.ToolRegistry
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A near-miss tool name has to come back with the name the model should have used.
 *
 * The two call-validation paths already answer "Did you mean ...?", but a call reaching the
 * executor directly got only the bare name. Measured on nemotron-cascade-2:30b, which asked for
 * `advance_editor` instead of `advance_code_editing` and had nothing to correct against, spending
 * the turn on it.
 */
class ToolExecutorUnknownToolTest {

    private val toolRegistry = mockk<ToolRegistry>()
    private val taskRepository = mockk<TaskRepository>()

    private lateinit var executor: ToolExecutor

    @BeforeEach
    fun setup() {
        every { toolRegistry.getTool(any()) } returns null
        every { toolRegistry.getToolNames() } returns listOf(
            "advance_code_editing", "read_file", "grep_search", "run_terminal_command",
        )
        every { taskRepository.findById(any()) } returns mockk<Task>(relaxed = true).also {
            every { it.mode } returns TaskMode.AGENT
        }
        executor = ToolExecutor(toolRegistry = toolRegistry, taskRepository = taskRepository)
    }

    @Test
    fun `an unknown tool name close to a real one names the real one`() = runTest {
        val error = assertFailsWith<ToolNotFoundException> {
            executor.executeTool(ToolCall(name = "advance_editor", params = emptyMap()))
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("advance_editor"), "the rejected name has to be stated: $message")
        assertTrue(
            message.contains("advance_code_editing"),
            "the model has to learn the name it should have called: $message",
        )
    }

    // A one-word typo is close enough for the suggester, and a direct pointer beats a list.
    @Test
    fun `a close typo gets a direct suggestion`() = runTest {
        val error = assertFailsWith<ToolNotFoundException> {
            executor.executeTool(ToolCall(name = "read_files", params = emptyMap()))
        }

        assertTrue(
            error.message.orEmpty().contains("Did you mean 'read_file'?"),
            "got: ${error.message}",
        )
    }

    // An invented name has no near match, so a suggestion would mislead - the model gets the real
    // list instead, which is what NameSuggestion's contract asks the caller to fall back to.
    @Test
    fun `a wildly invented name gets the tool list, not a wrong guess`() = runTest {
        val error = assertFailsWith<ToolNotFoundException> {
            executor.executeTool(ToolCall(name = "summon_the_kraken", params = emptyMap()))
        }

        val message = error.message.orEmpty()
        assertFalse(message.contains("Did you mean"), "a wrong suggestion is worse than none: $message")
        assertTrue(message.contains("Available tools:"), "the model needs something to pick from: $message")
        assertTrue(message.contains("grep_search"), "the list has to be the real one: $message")
    }
}
