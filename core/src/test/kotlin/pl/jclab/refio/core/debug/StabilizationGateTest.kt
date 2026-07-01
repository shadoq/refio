package pl.jclab.refio.core.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the stabilization gate's aggregation: a pass-rate over N runs plus a baseline/floor verdict.
 *
 * WHY this matters: the gate is the instrument that lets us cut brittle scaffolding safely - it must
 * report the same pass-rate a human would count by hand, classify why runs failed, and turn red the
 * moment the rate regresses below the floor or the baseline. These are the rules a single flaky run
 * must never be allowed to hide.
 */
class StabilizationGateTest {

    private fun rec(
        verdict: String,
        failureMode: String = "none",
        scenario: String = "s1"
    ): String =
        """{"scenario":"$scenario","model":"ollama/qwen","run":1,"verdict":"$verdict",""" +
            """"failure_mode":"$failureMode","status":"SUCCESS","costUsd":0.0,"tokensOut":100,"reasons":[]}"""

    private fun jsonl(vararg lines: String) = lines.joinToString("\n")

    @Test
    fun `pass-rate is passed over total`() {
        // 4 of 5 runs delivered -> 0.8. The single failure is bucketed by its mode.
        val records = StabilizationGate.parseResults(
            jsonl(rec("PASS"), rec("PASS"), rec("PASS"), rec("PASS"), rec("FAIL", "overflow"))
        )
        val report = StabilizationGate.aggregate(records)
        assertEquals(5, report.total)
        assertEquals(4, report.passed)
        assertEquals(0.8, report.passRate, 1e-9)
        assertEquals(mapOf("overflow" to 1), report.byFailureMode)
        assertEquals(1, report.failed.size)
    }

    @Test
    fun `byFailureMode counts each bucket and passes never appear there`() {
        val records = StabilizationGate.parseResults(
            jsonl(
                rec("PASS"), rec("PASS"),
                rec("FAIL", "overflow"), rec("FAIL", "overflow"),
                rec("FAIL", "build-fail")
            )
        )
        val report = StabilizationGate.aggregate(records)
        assertEquals(0.4, report.passRate, 1e-9)
        assertEquals(mapOf("build-fail" to 1, "overflow" to 2), report.byFailureMode)
    }

    @Test
    fun `per-scenario pass-rate is reported`() {
        val records = StabilizationGate.parseResults(
            jsonl(
                rec("PASS", scenario = "a"), rec("FAIL", "overflow", scenario = "a"),
                rec("PASS", scenario = "b"), rec("PASS", scenario = "b")
            )
        )
        val report = StabilizationGate.aggregate(records)
        assertEquals(0.5, report.byScenario["a"]!!, 1e-9)
        assertEquals(1.0, report.byScenario["b"]!!, 1e-9)
    }

    @Test
    fun `empty input yields zero pass-rate without dividing by zero, and is red`() {
        val report = StabilizationGate.aggregate(emptyList())
        assertEquals(0, report.total)
        assertEquals(0.0, report.passRate, 1e-9)
        assertFalse(StabilizationGate.decide(report, minPassRate = 1.0).green)
    }

    @Test
    fun `blank and malformed lines are skipped, not fatal`() {
        val records = StabilizationGate.parseResults(
            "not json\n\n   \n" + rec("PASS") + "\n{ broken"
        )
        assertEquals(1, records.size)
        assertTrue(records.single().passed)
    }

    @Test
    fun `failure_mode snake_case maps onto the camelCase field`() {
        val r = StabilizationGate.parseResults(rec("FAIL", "wrong-output")).single()
        assertEquals("wrong-output", r.failureMode)
        assertFalse(r.passed)
    }

    @Test
    fun `decide is green at or above the floor, red below it`() {
        val report = StabilizationGate.aggregate(
            StabilizationGate.parseResults(jsonl(rec("PASS"), rec("PASS"), rec("PASS"), rec("PASS"), rec("FAIL", "loop")))
        )
        assertTrue(StabilizationGate.decide(report, minPassRate = 0.75).green)
        assertFalse(StabilizationGate.decide(report, minPassRate = 0.9).green)
    }

    @Test
    fun `decide flags a regression below baseline beyond tolerance`() {
        // 0.7 observed vs 0.9 baseline: a 0.05 tolerance is breached (red); a 0.25 tolerance absorbs it.
        val report = StabilizationGate.aggregate(
            StabilizationGate.parseResults(
                jsonl(rec("PASS"), rec("PASS"), rec("PASS"), rec("PASS"), rec("PASS"), rec("PASS"), rec("PASS"),
                    rec("FAIL", "loop"), rec("FAIL", "loop"), rec("FAIL", "loop"))
            )
        )
        assertEquals(0.7, report.passRate, 1e-9)
        assertFalse(StabilizationGate.decide(report, baseline = 0.9, minPassRate = 0.0, tolerance = 0.05).green)
        assertTrue(StabilizationGate.decide(report, baseline = 0.9, minPassRate = 0.0, tolerance = 0.25).green)
    }

    /** A report with scenario `a` at 0.5 (1 of 2) and scenario `b` at 1.0 (2 of 2). */
    private fun abReport() = StabilizationGate.aggregate(
        StabilizationGate.parseResults(
            jsonl(
                rec("PASS", scenario = "a"), rec("FAIL", "overflow", scenario = "a"),
                rec("PASS", scenario = "b"), rec("PASS", scenario = "b")
            )
        )
    )

    @Test
    fun `decidePerScenario holds each scenario to its own baseline`() {
        val decision = StabilizationGate.decidePerScenario(
            abReport(), mapOf("a" to 0.5, "b" to 1.0), minPassRate = 0.0
        )
        assertTrue(decision.green, "each scenario meets its own baseline")
        assertEquals(2, decision.scenarios.size)
    }

    @Test
    fun `decidePerScenario reds the whole run when one scenario regresses below its baseline`() {
        val decision = StabilizationGate.decidePerScenario(
            abReport(), mapOf("a" to 0.8, "b" to 1.0), minPassRate = 0.0
        )
        assertFalse(decision.green, "scenario a dropped from 0.8 to 0.5")
        assertFalse(decision.scenarios.first { it.scenario == "a" }.green)
        assertTrue(decision.scenarios.first { it.scenario == "b" }.green)
    }

    @Test
    fun `decidePerScenario falls back to the floor for scenarios without a baseline entry`() {
        val report = abReport()
        // No baselines: scenario a (0.5) fails an 0.8 floor but clears a 0.5 floor.
        assertFalse(StabilizationGate.decidePerScenario(report, emptyMap(), minPassRate = 0.8).scenarios.first { it.scenario == "a" }.green)
        assertTrue(StabilizationGate.decidePerScenario(report, emptyMap(), minPassRate = 0.5).green)
    }

    @Test
    fun `parseBaseline reads scenario pass-rates and treats malformed input as empty`() {
        val parsed = StabilizationGate.parseBaseline("""{"a":0.8,"b":1.0}""")
        assertEquals(0.8, parsed["a"]!!, 1e-9)
        assertEquals(1.0, parsed["b"]!!, 1e-9)
        assertTrue(StabilizationGate.parseBaseline("not json").isEmpty())
    }

    @Test
    fun `baselineFrom then baselineJson then parseBaseline round-trips`() {
        val snapshot = StabilizationGate.baselineFrom(abReport())
        val roundTripped = StabilizationGate.parseBaseline(StabilizationGate.baselineJson(snapshot))
        assertEquals(snapshot, roundTripped)
    }

    @Test
    fun `historyEntry round-trips through jsonl`() {
        val entry = StabilizationGate.historyEntry(abReport(), "abc1234", 1_700_000_000_000L)
        val parsed = StabilizationGate.parseHistory(StabilizationGate.historyEntryJson(entry))
        assertEquals(1, parsed.size)
        assertEquals(entry, parsed.single())
        assertEquals("abc1234", parsed.single().commit)
        assertEquals(0.5, parsed.single().byScenario["a"]!!, 1e-9)
    }

    @Test
    fun `renderTrend lists overall and per-scenario pass-rates and handles empty history`() {
        assertEquals("no history", StabilizationGate.renderTrend(emptyList()))
        val history = listOf(
            StabilizationGate.historyEntry(abReport(), "c1", 1L),
            StabilizationGate.historyEntry(abReport(), "c2", 2L),
        )
        val rendered = StabilizationGate.renderTrend(history)
        assertTrue(rendered.contains("overall"))
        assertTrue(rendered.contains("a:"))
        assertTrue(rendered.contains("b:"))
        assertTrue(rendered.contains("c1") && rendered.contains("c2"))
    }
}
