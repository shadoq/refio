package pl.jclab.refio.core.services.turn

import io.mockk.mockk
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.services.ToolExecutor
import pl.jclab.refio.core.services.ToolResultSummarizer
import pl.jclab.refio.core.tools.base.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks what the approval panel says about a command it is asking the user to approve.
 *
 * WHY this matters: the panel renders this one line and nothing else. When it read just
 * "run_terminal_command", approving was a blind yes - `cat README.md` and `cat ~/.ssh/id_rsa` looked
 * identical. The whole point of routing shell tools through ASK is that the user reads the command.
 */
class TurnToolExecutorDescriptionTest {

    private val executor = TurnToolExecutor(
        toolExecutor = mockk<ToolExecutor>(),
        toolRegistry = mockk<ToolRegistry>(relaxed = true),
        subtaskRepository = mockk<SubtaskRepository>(relaxed = true),
        toolResultSummarizer = mockk<ToolResultSummarizer>()
    )

    private fun describe(name: String, arguments: String) =
        executor.buildToolDescription(ToolCallData(id = "c1", name = name, arguments = arguments))

    @Test
    fun `a terminal command is shown in full`() {
        val description = describe("run_terminal_command", """{"command":"cat ~/.ssh/id_rsa"}""")

        assertTrue(
            description.contains("cat ~/.ssh/id_rsa"),
            "the user must see WHAT they approve, got: $description"
        )
    }

    @Test
    fun `a background command is shown too`() {
        val description = describe("run_process_background", """{"command":"npm run dev"}""")

        assertTrue(description.contains("npm run dev"), "got: $description")
    }

    @Test
    fun `a very long command keeps its head - that is where the program name is`() {
        val command = "curl http://example.com/x " + "a".repeat(1_000)

        val description = describe("run_terminal_command", """{"command":"$command"}""")

        assertTrue(description.contains("curl http://example.com/x"), "the head must survive truncation")
        assertTrue(
            description.length < command.length,
            "an unbounded command must not be pasted whole into a one-line panel"
        )
    }

    @Test
    fun `a multi-line script is folded into one line`() {
        // A one-line panel would otherwise show only "cd build" and hide what actually runs.
        val description = describe("run_terminal_command", """{"command":"cd build\nrm -rf *"}""")

        assertTrue(description.contains("cd build rm -rf *"), "got: $description")
    }

    @Test
    fun `a call with no command still describes the tool`() {
        assertEquals("run_terminal_command", describe("run_terminal_command", "{}"))
    }
}
