package pl.jclab.refio.core.debug

import com.google.gson.annotations.SerializedName
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.core.utils.GsonInstance.prettyGson

/**
 * One per-run verdict record, as emitted by the e2e runner into `results.jsonl` when E2E_OUT_DIR is
 * set. The runner is the only place that knows the verdict together with the run.json metrics, so it
 * writes this record; the gate only aggregates. Field names match the runner's JSON exactly.
 */
data class GateRunRecord(
    val scenario: String = "",
    val model: String = "",
    val run: Int = 0,
    /** "PASS" or "FAIL". */
    val verdict: String = "",
    @SerializedName("failure_mode") val failureMode: String = "none",
    val status: String = "",
    val costUsd: Double = 0.0,
    val tokensOut: Long = 0,
    val reasons: List<String> = emptyList(),
) {
    val passed: Boolean get() = verdict.equals("PASS", ignoreCase = true)
}

/** Aggregate outcome over N runs. */
data class GateReport(
    val total: Int,
    val passed: Int,
    val passRate: Double,
    /** Count of failing runs per failure mode (passes never appear here). */
    val byFailureMode: Map<String, Int>,
    /** Pass-rate per scenario, for multi-scenario runs. */
    val byScenario: Map<String, Double>,
    val failed: List<GateRunRecord>,
)

/** Verdict of the report against an absolute floor and an optional baseline. */
data class GateDecision(
    val green: Boolean,
    val passRate: Double,
    val baseline: Double?,
    /** passRate - baseline, or null when no baseline was supplied. */
    val delta: Double?,
    val minPassRate: Double,
    val reason: String,
)

/** Per-scenario verdict against that scenario's own baseline entry. */
data class ScenarioDecision(
    val scenario: String,
    val passRate: Double,
    val baseline: Double?,
    /** passRate - baseline, or null when the scenario has no baseline entry. */
    val delta: Double?,
    val green: Boolean,
    val reason: String,
)

/** Verdict of a report against a per-scenario baseline map plus a shared floor. */
data class GatePerScenarioDecision(
    val green: Boolean,
    val minPassRate: Double,
    val tolerance: Double,
    val scenarios: List<ScenarioDecision>,
)

/** One line in e2e-history.jsonl: a gate run summary attributed to a commit, for trend tracking. */
data class GateHistoryEntry(
    val commit: String = "",
    @SerializedName("timestamp_ms") val timestampMs: Long = 0,
    val total: Int = 0,
    val passed: Int = 0,
    val passRate: Double = 0.0,
    val byScenario: Map<String, Double> = emptyMap(),
)

/**
 * Pure aggregation of an e2e gate run: parse `results.jsonl`, compute a pass-rate and failure-mode
 * breakdown, and decide green/red against a floor + baseline. No I/O, no LLM - the unit-tested core
 * that both the CLI `--gate` and any future self-improvement loop call.
 */
object StabilizationGate {

    /** Parse a `results.jsonl` blob. Blank and malformed lines are skipped, never thrown. */
    fun parseResults(jsonl: String): List<GateRunRecord> =
        jsonl.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line -> runCatching { gson.fromJson(line, GateRunRecord::class.java) }.getOrNull() }
            .filter { it.scenario.isNotEmpty() }
            .toList()

    fun aggregate(records: List<GateRunRecord>): GateReport {
        val total = records.size
        val passed = records.count { it.passed }
        val passRate = if (total == 0) 0.0 else passed.toDouble() / total
        val failed = records.filterNot { it.passed }
        val byFailureMode = failed
            .groupingBy { it.failureMode.ifBlank { "unknown" } }
            .eachCount()
            .toSortedMap()
        val byScenario = records
            .groupBy { it.scenario }
            .mapValues { (_, group) -> group.count { it.passed }.toDouble() / group.size }
            .toSortedMap()
        return GateReport(total, passed, passRate, byFailureMode, byScenario, failed)
    }

    /**
     * Green when the observed pass-rate clears the absolute [minPassRate] floor AND, if a [baseline]
     * is given, has not dropped below it by more than [tolerance]. With no baseline only the floor
     * applies. An empty report (no runs) is always red.
     */
    fun decide(
        report: GateReport,
        baseline: Double? = null,
        minPassRate: Double = 1.0,
        tolerance: Double = 0.0,
    ): GateDecision {
        val delta = baseline?.let { report.passRate - it }
        val belowFloor = report.passRate < minPassRate
        val regressed = baseline != null && report.passRate < baseline - tolerance
        val green = report.total > 0 && !belowFloor && !regressed
        val reason = when {
            report.total == 0 -> "no runs recorded"
            belowFloor -> "pass-rate ${pct(report.passRate)} below floor ${pct(minPassRate)}"
            regressed -> "pass-rate ${pct(report.passRate)} regressed vs baseline ${pct(baseline!!)} " +
                "(tolerance ${pct(tolerance)})"
            else -> "pass-rate ${pct(report.passRate)} ok"
        }
        return GateDecision(green, report.passRate, baseline, delta, minPassRate, reason)
    }

    /**
     * Decide green/red PER SCENARIO against a [baselines] map (scenario -> expected pass-rate).
     * Each scenario is green when its pass-rate clears the [minPassRate] floor AND, if it has a
     * baseline entry, has not dropped below it by more than [tolerance]. The overall verdict is
     * green only when every scenario is green and at least one run was recorded. Scenarios without
     * a baseline entry are held to the floor alone; baseline entries for scenarios absent from the
     * run are ignored (a missing scenario is not silently green).
     */
    fun decidePerScenario(
        report: GateReport,
        baselines: Map<String, Double>,
        minPassRate: Double = 1.0,
        tolerance: Double = 0.0,
    ): GatePerScenarioDecision {
        val scenarios = report.byScenario.map { (scenario, passRate) ->
            val baseline = baselines[scenario]
            val delta = baseline?.let { passRate - it }
            val belowFloor = passRate < minPassRate
            val regressed = baseline != null && passRate < baseline - tolerance
            val green = !belowFloor && !regressed
            val reason = when {
                belowFloor -> "pass-rate ${pct(passRate)} below floor ${pct(minPassRate)}"
                regressed -> "pass-rate ${pct(passRate)} regressed vs baseline ${pct(baseline!!)} " +
                    "(tolerance ${pct(tolerance)})"
                baseline != null -> "pass-rate ${pct(passRate)} ok vs baseline ${pct(baseline)}"
                else -> "pass-rate ${pct(passRate)} ok (no baseline)"
            }
            ScenarioDecision(scenario, passRate, baseline, delta, green, reason)
        }
        val green = report.total > 0 && scenarios.all { it.green }
        return GatePerScenarioDecision(green, minPassRate, tolerance, scenarios)
    }

    /** Snapshot the current per-scenario pass-rates as a baseline map (scenario -> pass-rate). */
    fun baselineFrom(report: GateReport): Map<String, Double> = report.byScenario

    /** Parse a baseline.json blob (`{"scenario": passRate, ...}`); malformed input yields an empty map. */
    @Suppress("UNCHECKED_CAST")
    fun parseBaseline(json: String): Map<String, Double> =
        runCatching {
            (gson.fromJson(json, Map::class.java) as? Map<String, Any?> ?: emptyMap())
                .mapNotNull { (key, value) -> (value as? Number)?.let { key to it.toDouble() } }
                .toMap()
        }.getOrDefault(emptyMap())

    /** Pretty JSON for a baseline map, keys sorted for stable diffs. */
    fun baselineJson(baselines: Map<String, Double>): String =
        prettyGson.toJson(baselines.toSortedMap())

    /** Build a history entry from a report + the commit it should be attributed to + a timestamp. */
    fun historyEntry(report: GateReport, commit: String, timestampMs: Long): GateHistoryEntry =
        GateHistoryEntry(commit, timestampMs, report.total, report.passed, report.passRate, report.byScenario)

    /** Compact single-line JSON suitable for appending to e2e-history.jsonl. */
    fun historyEntryJson(entry: GateHistoryEntry): String = gson.toJson(entry)

    /** Parse an e2e-history.jsonl blob; blank and malformed lines are skipped, never thrown. */
    fun parseHistory(jsonl: String): List<GateHistoryEntry> =
        jsonl.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line -> runCatching { gson.fromJson(line, GateHistoryEntry::class.java) }.getOrNull() }
            .toList()

    /**
     * Render a compact per-scenario pass-rate trend over the last [lastN] history entries (oldest to
     * newest). Pure text for the CLI trend reporter; an empty history yields "no history".
     */
    fun renderTrend(history: List<GateHistoryEntry>, lastN: Int = 10): String {
        if (history.isEmpty()) return "no history"
        val recent = history.takeLast(lastN)
        val commits = recent.joinToString(" ") { it.commit.take(7).ifBlank { "???????" } }
        val scenarios = recent.flatMap { it.byScenario.keys }.toSortedSet()
        val sb = StringBuilder()
        sb.appendLine("trend (last ${recent.size} runs, oldest->newest; commits: $commits)")
        sb.appendLine("  overall: " + recent.joinToString(" ") { pct(it.passRate) })
        scenarios.forEach { scenario ->
            sb.appendLine("  $scenario: " + recent.joinToString(" ") { it.byScenario[scenario]?.let(::pct) ?: "-" })
        }
        return sb.toString().trimEnd()
    }

    private fun pct(v: Double): String = "${(v * 100).toInt()}%"

    /** Machine-readable report+decision as pretty JSON (keeps Gson inside :core). */
    fun reportJson(report: GateReport, decision: GateDecision): String =
        prettyGson.toJson(mapOf("report" to report, "decision" to decision))

    /** Machine-readable report + per-scenario decision as pretty JSON. */
    fun reportJson(report: GateReport, decision: GatePerScenarioDecision): String =
        prettyGson.toJson(mapOf("report" to report, "decision" to decision))
}
