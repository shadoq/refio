package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the tool classification behind the "read forever, never deliver" consolidation nudge.
 *
 * WHY this matters: a top-level AGENT run that keeps reading/searching without ever writing,
 * persisting, or delivering loses its own evidence — older tool outputs are compressed out of
 * RECENT_WORK before the model assembles a result (observed: session e05ede28 read 25+ adapter
 * files over 38 min, wrote nothing, user cancelled). The nudge only helps if "still just reading"
 * is told apart from "made progress worth keeping" — that boundary is exactly these two functions.
 */
class TurnToolExecutorClassificationTest {

    // ---- isGatheringCall: what accumulates the read-only spree counter ----

    @Test
    fun `reads and searches are gathering calls`() {
        // These are the calls whose unbounded repetition is the pathology — they must accumulate.
        assertTrue(TurnToolExecutor.isGatheringCall("read_file", ToolMode.READ_ONLY, ToolCategory.DATA_PRODUCING))
        assertTrue(TurnToolExecutor.isGatheringCall("grep_search", ToolMode.READ_ONLY, ToolCategory.DATA_PRODUCING))
        assertTrue(TurnToolExecutor.isGatheringCall("file_search", ToolMode.READ_ONLY, ToolCategory.DATA_PRODUCING))
    }

    @Test
    fun `write tools are not gathering`() {
        // Writing the deliverable is the opposite of gathering — it must never inflate the spree.
        assertFalse(TurnToolExecutor.isGatheringCall("create_new_file", ToolMode.WRITE, ToolCategory.FILE_PRODUCING))
    }

    @Test
    fun `system state tools are not gathering`() {
        // memory/tasks/messages are agent-state plumbing, not "reading the codebase". A model that
        // persists to memory between reads is consolidating, not gathering — must not accumulate.
        assertFalse(TurnToolExecutor.isGatheringCall("memory", ToolMode.READ_ONLY, ToolCategory.SYSTEM))
        assertFalse(TurnToolExecutor.isGatheringCall("tasks", ToolMode.READ_ONLY, ToolCategory.SYSTEM))
    }

    @Test
    fun `think is never gathering regardless of its category`() {
        // `think` is reflection between actions. It must neither inflate the spree nor (below)
        // reset it — it carries no information about read-vs-deliver progress.
        assertFalse(TurnToolExecutor.isGatheringCall("think", ToolMode.READ_ONLY, ToolCategory.SYSTEM))
        assertFalse(TurnToolExecutor.isGatheringCall("think", ToolMode.READ_ONLY, ToolCategory.DATA_PRODUCING))
    }

    // ---- isConsolidationProgressCall: what RESETS the read-only spree counter ----

    @Test
    fun `writing a file is progress`() {
        // The whole point: producing the deliverable resets the "never delivered" streak.
        assertTrue(TurnToolExecutor.isConsolidationProgressCall("create_new_file", ToolMode.WRITE, ToolCategory.FILE_PRODUCING))
        assertTrue(TurnToolExecutor.isConsolidationProgressCall("advance_code_editing", ToolMode.WRITE, ToolCategory.FILE_PRODUCING))
    }

    @Test
    fun `persisting or delivering via system tools is progress`() {
        // Persisting findings (memory) IS the consolidation the nudge asks for — calling it must
        // reset the streak so the model is not nagged after it complied. Same for delivering/asking.
        assertTrue(TurnToolExecutor.isConsolidationProgressCall("memory", ToolMode.READ_ONLY, ToolCategory.SYSTEM))
        assertTrue(TurnToolExecutor.isConsolidationProgressCall("tasks", ToolMode.READ_ONLY, ToolCategory.SYSTEM))
        assertTrue(TurnToolExecutor.isConsolidationProgressCall("answer_message", ToolMode.READ_ONLY, ToolCategory.SYSTEM))
    }

    @Test
    fun `delegating the work is progress`() {
        // Offloading to a stronger model / subagent is a legitimate escape from the read spree.
        assertTrue(TurnToolExecutor.isConsolidationProgressCall("delegate_to_strong_model", ToolMode.WRITE, ToolCategory.SYSTEM))
        assertTrue(TurnToolExecutor.isConsolidationProgressCall("invoke_subagent", ToolMode.WRITE, ToolCategory.SYSTEM))
    }

    @Test
    fun `reads and think do not count as progress`() {
        // A batch of pure reads (or only `think`) made no consolidation progress — the streak must
        // keep climbing toward the nudge, never silently reset.
        assertFalse(TurnToolExecutor.isConsolidationProgressCall("read_file", ToolMode.READ_ONLY, ToolCategory.DATA_PRODUCING))
        assertFalse(TurnToolExecutor.isConsolidationProgressCall("grep_search", ToolMode.READ_ONLY, ToolCategory.DATA_PRODUCING))
        assertFalse(TurnToolExecutor.isConsolidationProgressCall("think", ToolMode.READ_ONLY, ToolCategory.SYSTEM))
    }

    @Test
    fun `read and progress classifications are mutually exclusive for the same call`() {
        // Sanity: no single call should both accumulate and reset — that would make the counter
        // ill-defined. (Holds because gathering excludes SYSTEM/WRITE, progress requires them.)
        val cases = listOf(
            Triple("read_file", ToolMode.READ_ONLY, ToolCategory.DATA_PRODUCING),
            Triple("create_new_file", ToolMode.WRITE, ToolCategory.FILE_PRODUCING),
            Triple("memory", ToolMode.READ_ONLY, ToolCategory.SYSTEM),
            Triple("think", ToolMode.READ_ONLY, ToolCategory.SYSTEM),
            Triple("invoke_subagent", ToolMode.WRITE, ToolCategory.SYSTEM),
        )
        for ((name, mode, category) in cases) {
            assertFalse(
                TurnToolExecutor.isGatheringCall(name, mode, category) &&
                    TurnToolExecutor.isConsolidationProgressCall(name, mode, category),
                "$name must not be both gathering and progress"
            )
        }
    }

    // ---- isConsolidationProgressCall: a no-op write is NOT progress (P1) ----

    @Test
    fun `a no-op write does not count as consolidation progress`() {
        // WHY: session f998771b / c19. advance_code_editing returned content identical to the file
        // (the editing model could not act on the description), yet it is mode=WRITE so it reset the
        // read-only spree counter — masking an ongoing read-forever loop that then ran to maxIterations.
        // A write that changed nothing is the OPPOSITE of progress; it must NOT reset the streak.
        assertFalse(
            TurnToolExecutor.isConsolidationProgressCall(
                "advance_code_editing", ToolMode.WRITE, ToolCategory.FILE_PRODUCING, isNoopWrite = true
            )
        )
        assertFalse(
            TurnToolExecutor.isConsolidationProgressCall(
                "create_new_file", ToolMode.WRITE, ToolCategory.FILE_PRODUCING, isNoopWrite = true
            )
        )
    }

    @Test
    fun `a real (non-no-op) write is still progress`() {
        // The default path is unchanged: a write that actually changed bytes resets the streak.
        assertTrue(
            TurnToolExecutor.isConsolidationProgressCall(
                "advance_code_editing", ToolMode.WRITE, ToolCategory.FILE_PRODUCING, isNoopWrite = false
            )
        )
    }

    @Test
    fun `the no-op flag only demotes writes, never system or delegation progress`() {
        // isNoopWrite only ever describes a WRITE diff. Defensive: even if passed true, persisting
        // (memory) / delegating remain progress — their "progress" is not a byte-diff. Guards against
        // gating the whole expression on the flag instead of just the WRITE branch.
        assertTrue(
            TurnToolExecutor.isConsolidationProgressCall(
                "memory", ToolMode.READ_ONLY, ToolCategory.SYSTEM, isNoopWrite = true
            )
        )
        assertTrue(
            TurnToolExecutor.isConsolidationProgressCall(
                "delegate_to_strong_model", ToolMode.WRITE, ToolCategory.SYSTEM, isNoopWrite = true
            )
        )
    }
}
