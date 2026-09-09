package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.services.ToolResultData
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks "did a file deliverable actually land this turn?" to the tool RESULTS, not to the fact that
 * the model asked for a write.
 *
 * WHY this matters: the turn's file-write tally feeds [TurnDeliverable], which decides whether a
 * turn that finishes without a clean sign-off is reported as delivered or as a failure. Counting
 * requested writes instead of landed ones let a turn whose only edit ERRORED sign itself off as
 * "the requested file changes were written this turn" while no file existed on disk (observed on
 * qwen3.6:35b: advance_code_editing gave up after 2 attempts at producing a code block, the turn
 * reported SUCCESS, and the e2e assertion was the only thing that noticed the file was missing).
 *
 * A missing result is deliberately treated as landed: it keeps the tally conservative on exit paths
 * that never collect results, so this narrows only the case we can prove went wrong.
 */
class TurnLandedFileWriteCountTest {

    private fun call(id: String, name: String) = ToolCallData(id = id, name = name, arguments = "{}")

    private fun result(toolCallId: String, success: Boolean) = ToolResultData(
        toolCallId = toolCallId,
        content = "",
        isSummarized = false,
        success = success,
    )

    private val isWrite: (String) -> Boolean = { it == "code_editing" || it == "create_new_file" }

    @Test
    fun `an edit that errored is not a delivered file`() {
        // The circuitsmith regression: the only substantive call failed, so the turn delivered
        // nothing and must not be able to sign itself off as a completed write.
        val calls = listOf(call("c1", "code_editing"))
        val results = listOf(result("c1", success = false))

        assertEquals(0, TurnToolExecutor.countLandedCalls(calls, results, isWrite))
    }

    @Test
    fun `an edit that succeeded is a delivered file`() {
        val calls = listOf(call("c1", "code_editing"))
        val results = listOf(result("c1", success = true))

        assertEquals(1, TurnToolExecutor.countLandedCalls(calls, results, isWrite))
    }

    @Test
    fun `a failed write alongside a successful one counts only the one that landed`() {
        // The common repair shape: the first edit misses, the model retries and lands it. Exactly
        // one file deliverable exists, so the tally must say one - not two, and not zero.
        val calls = listOf(call("c1", "code_editing"), call("c2", "code_editing"))
        val results = listOf(result("c1", success = false), result("c2", success = true))

        assertEquals(1, TurnToolExecutor.countLandedCalls(calls, results, isWrite))
    }

    @Test
    fun `a call with no recorded result stays counted`() {
        // Conservative fallback: exit paths that never collect results must keep behaving as before
        // rather than start reporting delivered work as a failure.
        val calls = listOf(call("c1", "code_editing"))

        assertEquals(1, TurnToolExecutor.countLandedCalls(calls, emptyList(), isWrite))
    }

    @Test
    fun `a successful non-write call is never a file deliverable`() {
        // run_terminal_command and friends are mode=WRITE but leave no file behind; the predicate
        // excludes them, and success must not smuggle them into the tally.
        val calls = listOf(call("c1", "read_file"), call("c2", "run_terminal_command"))
        val results = listOf(result("c1", success = true), result("c2", success = true))

        assertEquals(0, TurnToolExecutor.countLandedCalls(calls, results, isWrite))
    }
}
