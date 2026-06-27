package pl.jclab.refio.core.debug

import java.util.concurrent.ConcurrentHashMap

/**
 * Records whether a task's prompt ever exceeded the model's context window (docs/0057 Tier 3).
 *
 * Context overflow is the silent failure local-first targets: when an input prompt is larger
 * than the window, Ollama truncates it from the head and the agent reasons over a partial
 * prompt while reporting success. The detection happens at two places — the **pre-send**
 * estimate inside [pl.jclab.refio.core.llm.adapters.OllamaAdapter] (the only reliable place
 * for Ollama, whose returned `prompt_eval_count` is already post-truncation) and a generic
 * post-response `inputTokens > window` check in [pl.jclab.refio.core.services.AgentTurnLoop]
 * for cloud providers (which report the true pre-truncation count and usually error rather
 * than truncate). Both funnel a `markOverflow(taskId)` here.
 *
 * [pl.jclab.refio.core.debug.SessionDebugExporter] reads it into `run.json.metrics.contextOverflow`,
 * which the e2e harness (docs/0061) treats as a failed run — a silent truncation must never
 * pass as success.
 *
 * Thread-safe process-global singleton keyed by `taskId`, matching the convention of
 * [pl.jclab.refio.core.services.monitoring.ModelUsageStats]. Reset via [reset] (tests only).
 */
object ContextOverflowTracker {

    private val overflowedTasks = ConcurrentHashMap.newKeySet<String>()

    /** Mark that [taskId]'s prompt overflowed the context window at least once. */
    fun markOverflow(taskId: String) {
        overflowedTasks.add(taskId)
    }

    /** True if [taskId] overflowed the window at any point this process. */
    fun didOverflow(taskId: String): Boolean = overflowedTasks.contains(taskId)

    /** Test-only: forget all recorded overflows. */
    fun reset() {
        overflowedTasks.clear()
    }
}
