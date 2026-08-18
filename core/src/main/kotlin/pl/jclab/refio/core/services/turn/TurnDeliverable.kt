package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.db.TaskMode

/**
 * Shared "did this turn already produce a deliverable?" predicate.
 *
 * Used at terminal stall / abort points to decide whether a non-clean finish should be a FAILURE
 * (the agent abandoned the request before producing anything) or a SUCCESS (the deliverable is
 * already on hand and only the sign-off / an optional self-verification step went wrong). This is
 * the single source of truth for that judgement so the guardian, the format hard-fail, and the
 * repetition abort all agree on deliverable-aware finalization.
 *
 *  - AGENT (main): a real FILE edit/create landed this turn → the file deliverable is on disk. The
 *    caller must pass the FILE-write count, NOT any write-mode call: run_terminal_command/run_code
 *    are mode=WRITE but leave no file, so a `mkdir`-and-stall must not read as a delivered turn.
 *  - PLAN: writes are structurally impossible; the deliverable IS the answer text, so a substantial
 *    reply (a real plan, not a bare "Let me produce a plan." intent stub) counts.
 *  - SUBAGENT: may be read-only (e.g. a code review) and deliver its whole answer AS prose, so a
 *    substantial reply counts even with zero writes - otherwise every review/analysis delegation
 *    would be judged as "delivered nothing".
 *
 * For the text cases length is a floor, not the whole test: a rambling announcement of work still
 * to come ("Let me now analyze all these files and then produce the plan…") clears any reasonable
 * character count while delivering nothing. It is rejected on shape instead - see [isIntentStub].
 * The two mistakes are not symmetric: crediting a stub costs the user one wasted turn, while
 * refusing a real answer throws finished work away and reports a false failure, so the shape test
 * is deliberately narrow and gives up on anything long enough to plausibly carry substance.
 */
object TurnDeliverable {

    /** Minimum PLAN-mode reply length (chars) to count as a produced answer vs a bare intent stub. */
    const val PLAN_DELIVERABLE_MIN_CHARS = 100

    /**
     * Past this length a single paragraph carries enough content that the intent-stub test stops
     * applying: whatever it opens with, it is no longer just an announcement.
     */
    private const val INTENT_STUB_MAX_CHARS = 400

    /** Openers that announce work still to come rather than report its outcome. */
    private val INTENT_OPENER = Regex(
        "^(ok(ay)?[,.!]?\\s+|sure[,.!]?\\s+|alright[,.!]?\\s+)?" +
            "(now[,.]?\\s+|next[,.]?\\s+|first[,.]?\\s+|then[,.]?\\s+)?" +
            "(i['’]?ll|i will|i am going to|i['’]?m going to|i need to|i have to|i should|" +
            "let me|let['’]?s|going to|proceeding to|starting to)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Markers of an answer with a shape: a list, a heading, a code block. */
    private val STRUCTURE_MARKER = Regex("^([-*+•]\\s|#{1,6}\\s|\\d+[.)]\\s|```)")

    fun produced(
        fileWriteToolsExecutedInTurn: Int,
        mode: TaskMode,
        finalResponse: String,
        isSubagent: Boolean = false,
    ): Boolean {
        if (fileWriteToolsExecutedInTurn > 0) {
            return true
        }
        if (mode != TaskMode.PLAN && !isSubagent) {
            return false
        }
        val answer = finalResponse.trim()
        return answer.length >= PLAN_DELIVERABLE_MIN_CHARS && !isIntentStub(answer)
    }

    /**
     * True when a top-level AGENT turn ended without success and without ever writing a file.
     *
     * This is the most informative fact about such a run and nothing recorded it, so the harness
     * bucketed every one of them as a generic INCOMPLETE. Measured across a 24-model sweep, three
     * models ended exactly this way on the same scenario - each announcing the edit it was about to
     * make and stopping - and the reports called it a loop, which sent the analysis down the wrong
     * path.
     *
     * The test is structural (did a file-write tool run), never a reading of the prose: matching
     * announcement-shaped text was tried as a turn guard and deliberately removed.
     *
     * Restricted to depth 0 and AGENT: a PLAN turn cannot write, and a read-only subagent delivers
     * its whole answer as prose, so neither says anything by writing nothing.
     */
    fun stalledWithoutWriting(
        success: Boolean,
        mode: TaskMode,
        depth: Int,
        fileWriteToolsExecutedInTurn: Int,
    ): Boolean = !success &&
        mode == TaskMode.AGENT &&
        depth == 0 &&
        fileWriteToolsExecutedInTurn == 0

    /**
     * True for a reply that only says what the model is about to do.
     *
     * Deliberately narrow, because a false positive costs a finished answer: the text must be short
     * enough to be an announcement, be a single unstructured paragraph (no list, heading or code
     * block - those carry the plan itself), and open by announcing a future action.
     */
    private fun isIntentStub(answer: String): Boolean {
        if (answer.length > INTENT_STUB_MAX_CHARS) {
            return false
        }
        val lines = answer.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size > 2 || lines.any { STRUCTURE_MARKER.containsMatchIn(it) }) {
            return false
        }
        return lines.firstOrNull()?.let { INTENT_OPENER.containsMatchIn(it) } ?: false
    }
}
