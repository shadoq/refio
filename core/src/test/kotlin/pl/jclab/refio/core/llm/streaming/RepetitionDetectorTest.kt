package pl.jclab.refio.core.llm.streaming

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [RepetitionDetector] — catches the "stuck in a loop" failure mode
 * where a weak streaming LLM produces the same block of text over and over.
 *
 * Regression anchor: this was introduced after qwen3.5:35b burned 522 seconds
 * of wall clock on the AiDevs domatowo task by repeating a ~500-char Python
 * code block 40+ times. Without this guardrail the loop was invisible in logs
 * until Ktor's HTTP timeout fired.
 *
 * Second regression (gpt-5.6-luna, STELLAR SOUND landing page): a comparison
 * table ending a row with 4 byte-identical `<td class="check">✓</td>` cells
 * tripped the old threshold-4 abort mid-generation, killing a valid `advance_code_editing`
 * stream and sending the agent into a costly recovery spiral. The threshold was
 * raised to 32 so legitimate bounded repetition of markup no longer fires while a
 * genuine unbounded decoder loop (40+) still does. With the 4096-char tail buffer
 * this restricts detection to blocks up to ~128 chars; larger-block runaway is left
 * to the OutputSizeLimiter (128 KB) and WallClockDeadline (180 s) backstops.
 */
class RepetitionDetectorTest {

    /**
     * Feed a repeated payload through a detector with [checkEveryNDeltas] = 1
     * so we don't have to wait for gating. Returns the first abort decision
     * or null if the detector never fired after [maxRounds] rounds.
     */
    private fun feedUntilAbort(
        payload: String,
        rounds: Int,
        checkEveryNDeltas: Int = 1,
        repeatThreshold: Int = 32
    ): StreamGuardrail.Decision.Abort? {
        val detector = RepetitionDetector(
            checkEveryNDeltas = checkEveryNDeltas,
            repeatThreshold = repeatThreshold
        )
        val acc = StringBuilder()
        repeat(rounds) {
            acc.append(payload)
            val tail = if (acc.length <= 4096) acc.toString() else acc.substring(acc.length - 4096)
            val decision = detector.onDelta(payload, acc.length, tail, 0L)
            if (decision is StreamGuardrail.Decision.Abort) return decision
        }
        return null
    }

    @Nested
    inner class TailRepetitionDetection {

        @Test
        fun `fires on 32 consecutive small-block repetitions`() {
            // The founding failure mode: a weak model chanting the same short line
            // over and over. ~51-char block × 32 = 1632 chars, well within the 4096 tail.
            val block = "Let me execute this Python code move transporter D8\n"
            val result = feedUntilAbort(block, rounds = 40)
            assertTrue(result != null, "Detector should fire once the loop reaches 32 copies")
            assertEquals("REPETITION_LOOP", result.code)
            assertTrue(
                result.reason.contains("back-to-back"),
                "Abort reason should mention back-to-back pattern, got: ${result.reason}"
            )
        }

        @Test
        fun `does not fire below the threshold (31 small-block repetitions)`() {
            // One short of the 32-copy threshold: a genuine loop keeps going past this,
            // but legitimate bounded repetition (e.g. many identical table cells) stops here.
            val block = "Let me execute this Python code move transporter D8\n"
            val result = feedUntilAbort(block, rounds = 31)
            assertEquals(null, result, "31 copies is below the threshold of 32 and must not fire")
        }

        @Test
        fun `still fires on a tight multi-char loop`() {
            // Guard: the low-information exemption must stay narrow and NOT swallow a
            // genuine multi-char loop. A 20-char two-distinct-char block repeated 32×.
            val block = "ab".repeat(10)  // 20 chars, distinct a/b; 20 × 32 = 640 <= 4096 tail
            val result = feedUntilAbort(block, rounds = 40)
            assertTrue(result != null, "A 2-distinct-char block loop must still fire at 32 copies")
            assertEquals("REPETITION_LOOP", result.code)
        }

        @Test
        fun `does not fire on large-block runaway - that is left to the size and wall-clock backstops`() {
            // A >128-char block cannot reach 32 copies inside the 4096-char tail
            // (128 × 32 = 4096), so this detector deliberately does NOT catch large-block
            // loops. Such a runaway is bounded by OutputSizeLimiter (128 KB) and
            // WallClockDeadline (180 s) instead. The block is a non-periodic sentence so
            // no shorter sub-period matches either.
            val block = "The quick brown fox jumps over the lazy dog while carrying forty-two parcels " +
                "to the distant village near the river bank under a bright autumn moon tonight.\n"  // ~155 chars
            val result = feedUntilAbort(block, rounds = 40)
            assertEquals(null, result, "Large-block loops are handed to the size/wall-clock limiters, not this detector")
        }

        @Test
        fun `does not fire on varied legitimate content`() {
            val detector = RepetitionDetector(checkEveryNDeltas = 1)
            val acc = StringBuilder()
            val deltas = listOf(
                "I'll start by analyzing the map structure. ",
                "Looking at the grid, row 5 appears to contain all roads A5-K5. ",
                "The B3 blocks are located at F0, G0, F1, G1 (top-right) and A9-C9, H9-I9 (bottom). ",
                "My plan: move the transporter along row 5 to reach the bottom blocks. ",
                "First I'll verify with getObjects. ",
                "Then I'll dismount scouts near the target. ",
                "Let me inspect A9 and B9 to find the partisan. ",
                "If empty, I'll try the bottom-right cluster at H9-I9. ",
                "Finally, I'll call the helicopter at the partisan's location. "
            )
            for (d in deltas) {
                acc.append(d)
                val tail = acc.toString()
                val decision = detector.onDelta(d, acc.length, tail, 0L)
                assertTrue(
                    decision is StreamGuardrail.Decision.Continue,
                    "Normal varied content must not fire: got $decision on delta '$d'"
                )
            }
        }

        @Test
        fun `does not fire when tail is too short`() {
            val detector = RepetitionDetector(checkEveryNDeltas = 1)
            // Minimum needed is minPeriod (20) × repeatThreshold (32) = 640 chars.
            // Feed 60 chars — below the floor, scan must be skipped.
            val short = "a".repeat(60)
            val decision = detector.onDelta(short, short.length, short, 0L)
            assertEquals(StreamGuardrail.Decision.Continue, decision)
        }

        @Test
        fun `respects checkEveryNDeltas gating`() {
            val detector = RepetitionDetector(checkEveryNDeltas = 50)
            val block = "x".repeat(100)
            val acc = StringBuilder()
            // Feed enough to trigger repetition structurally, but only 10 deltas
            // — below the gate of 50.
            repeat(10) {
                acc.append(block)
                val decision = detector.onDelta(block, acc.length, acc.toString(), 0L)
                assertTrue(
                    decision is StreamGuardrail.Decision.Continue,
                    "Gate should prevent check from running at delta #${it + 1}"
                )
            }
        }

        @Test
        fun `detector is reusable after reset`() {
            val detector = RepetitionDetector(checkEveryNDeltas = 1)
            val loopBlock = "loop block of text that repeats "  // 32 chars; 32 × 32 = 1024 <= 4096 tail
            val acc = StringBuilder()

            // Feed 32 repetitions to reach the abort threshold.
            var firstFired = false
            repeat(32) {
                acc.append(loopBlock)
                val tail = if (acc.length <= 4096) acc.toString() else acc.substring(acc.length - 4096)
                val decision = detector.onDelta(loopBlock, acc.length, tail, 0L)
                if (decision is StreamGuardrail.Decision.Abort) firstFired = true
            }
            assertTrue(firstFired, "Detector should have fired once 32 copies accumulated")

            // Reset and re-feed — should fire again (not be stuck).
            detector.reset()
            val acc2 = StringBuilder()
            var secondFired = false
            repeat(32) {
                acc2.append(loopBlock)
                val tail = if (acc2.length <= 4096) acc2.toString() else acc2.substring(acc2.length - 4096)
                val decision = detector.onDelta(loopBlock, acc2.length, tail, 0L)
                if (decision is StreamGuardrail.Decision.Abort) secondFired = true
            }
            assertTrue(secondFired, "After reset, detector should fire again")
        }
    }

    @Nested
    inner class FalsePositiveResistance {

        @Test
        fun `does not fire on markdown list with similar bullets`() {
            val detector = RepetitionDetector(checkEveryNDeltas = 1)
            val acc = StringBuilder()
            // Bulleted list where each bullet starts with "- " but the content differs.
            // A naive "substring appears N times" detector would catch "- " repeating,
            // but RepetitionDetector looks for back-to-back EXACT block matches so
            // this should not fire.
            val bullets = listOf(
                "- read the project instructions carefully\n",
                "- identify the three execution modes (CHAT, PLAN, AGENT)\n",
                "- understand the tool registry and how tools are filtered\n",
                "- check the composition root in CoreApiRouter\n",
                "- review the 12 domain routers exposed to callers\n",
                "- trace a request from UI through the service layer\n",
                "- verify the LLM adapter selection path\n",
                "- finally, run the test suite and confirm everything passes\n"
            )
            for (b in bullets) {
                acc.append(b)
                val tail = acc.toString()
                val decision = detector.onDelta(b, acc.length, tail, 0L)
                assertTrue(
                    decision is StreamGuardrail.Decision.Continue,
                    "Markdown bullets must not trip the detector: got $decision"
                )
            }
        }

        @Test
        fun `does not fire on a long single-character ASCII rule`() {
            // Regression (session 81253ffc, 2026-05): a user-requested ASCII
            // architecture diagram contained a horizontal rule of 80+ box-drawing
            // chars ("────…"). A single repeated character is periodic at every
            // period and used to trip REPETITION_LOOP, aborting a legitimate diagram.
            val detector = RepetitionDetector(checkEveryNDeltas = 1)
            val rule = "─".repeat(120)
            val decision = detector.onDelta(rule, rule.length, rule, 0L)
            assertTrue(
                decision is StreamGuardrail.Decision.Continue,
                "A single-character ASCII rule must not trip the detector: got $decision"
            )
        }

        @Test
        fun `does not fire on common single-character separators`() {
            for (ch in listOf("=", ".", "-", "*", "_", " ")) {
                val detector = RepetitionDetector(checkEveryNDeltas = 1)
                val rule = ch.repeat(200)
                val decision = detector.onDelta(rule, rule.length, rule, 0L)
                assertTrue(
                    decision is StreamGuardrail.Decision.Continue,
                    "Separator run of '$ch' must not trip the detector: got $decision"
                )
            }
        }

        @Test
        fun `does not fire on a tile-map level-data array of mostly-empty rows`() {
            // Regression (session 7e87c668, 2026-06): qwen3.5:122b generating a C64 game's
            // LEVELS array — back-to-back rows of dots ("....................", …) tripped a
            // threshold-4 REPETITION_LOOP abort and killed a valid stream. Tile maps and
            // ASCII level data are legitimately repetitive (mostly the fill char + indent).
            val detector = RepetitionDetector(checkEveryNDeltas = 1)
            val acc = StringBuilder()
            // One JS source line of a level row: 16-space indent + a 58-dot string + ` +`.
            val row = " ".repeat(16) + "\"" + ".".repeat(58) + "\" +\n"
            var aborted = false
            repeat(8) {
                acc.append(row)
                val tail = acc.toString()
                val decision = detector.onDelta(row, acc.length, tail, 0L)
                if (decision is StreamGuardrail.Decision.Abort) aborted = true
            }
            assertTrue(!aborted, "Tile-map level rows must not trip the loop detector")
        }

        @Test
        fun `does not fire on a comparison-table row of byte-identical cells`() {
            // Regression (gpt-5.6-luna, STELLAR SOUND page, 2026-07): the editor LLM
            // streamed a feature comparison table whose row ended with several
            // byte-identical `<td class="check">✓</td>` cells. The old threshold-4 abort
            // fired on this valid markup, killing the advance_code_editing stream and
            // triggering a costly recovery spiral. Even a wide (8-column) table stays
            // well below the 32-copy threshold and must not fire.
            val detector = RepetitionDetector(checkEveryNDeltas = 1)
            val acc = StringBuilder()
            val cell = "<td class=\"check\" aria-label=\"Yes\">✓</td>"  // 41 chars, the real block
            var aborted = false
            // 8 identical cells back-to-back (a generous, still-legitimate table row).
            repeat(8) {
                acc.append(cell)
                val decision = detector.onDelta(cell, acc.length, acc.toString(), 0L)
                if (decision is StreamGuardrail.Decision.Abort) aborted = true
            }
            assertTrue(!aborted, "A row of identical table cells below threshold must not trip the detector")
        }

        @Test
        fun `still fires when identical cells repeat far past any real table (32+)`() {
            // The high threshold does not disable the guard: 32+ byte-identical cells in
            // a row is no longer a table, it is a stuck decoder, and must still abort.
            val cell = "<td class=\"check\" aria-label=\"Yes\">✓</td>"  // 41 chars; 41 × 32 = 1312 <= 4096
            val result = feedUntilAbort(cell, rounds = 40)
            assertTrue(result != null, "32+ identical cells is degenerate and must fire")
            assertEquals("REPETITION_LOOP", result.code)
        }

        @Test
        fun `does not fire on JSON list with similar shapes`() {
            val detector = RepetitionDetector(checkEveryNDeltas = 1)
            val acc = StringBuilder()
            val items = (1..15).map { """{"id": $it, "name": "item-$it", "active": true, "description": "entry number $it with some padding to make the shape substantial"}""" + "\n" }
            for (item in items) {
                acc.append(item)
                val decision = detector.onDelta(item, acc.length, acc.toString(), 0L)
                assertTrue(
                    decision is StreamGuardrail.Decision.Continue,
                    "JSON list with varying IDs must not trip the detector: got $decision"
                )
            }
        }
    }
}
