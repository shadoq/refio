package pl.jclab.refio.core.debug

import java.util.concurrent.ConcurrentHashMap

/**
 * What the context builder had to leave out to fit the window, summed over a whole run.
 *
 * The builder computes a full decision every iteration and then overwrites it, so only the last
 * iteration was ever observable and only from inside the IDE. Headless runs saw nothing at all.
 * Accumulated here, it answers whether a long task quietly loses its own earlier findings.
 */
data class ContextUsage(
    /** Token budget of the most recent iteration. */
    val budgetTokens: Int = 0,
    /** Tokens the most recent iteration actually used. */
    val usedTokens: Int = 0,
    /** Conversation messages trimmed away across the whole run. */
    val droppedMessages: Int = 0,
    /** Older tool steps omitted from the prompt across the whole run. */
    val droppedSteps: Int = 0,
    /** Section name to the number of iterations in which it did not fit at all. */
    val drops: Map<String, Int> = emptyMap(),
) {
    companion object {
        val NONE = ContextUsage()
    }
}

/**
 * Per-task context accounting on its way to `run.json.metrics.context`. Thread-safe process-global
 * singleton keyed by `taskId`, mirroring [TurnVerificationTracker].
 *
 * Unlike the other trackers this one ACCUMULATES: budget and usage are the latest iteration's, but
 * the three loss counters are sums over the run, because "one section fell out once" and "it fell
 * out on every single iteration" are very different findings and only the sum tells them apart.
 */
object TurnContextTracker {

    private val usage = ConcurrentHashMap<String, ContextUsage>()

    /**
     * Fold one iteration's context decision into the task's running total.
     *
     * @param droppedSections names of the sections that did not fit at all this iteration
     */
    fun recordIteration(
        taskId: String,
        budgetTokens: Int,
        usedTokens: Int,
        droppedSections: List<String>,
    ) {
        usage.compute(taskId) { _, prev ->
            val base = prev ?: ContextUsage.NONE
            val drops = base.drops.toMutableMap()
            droppedSections.forEach { drops[it] = (drops[it] ?: 0) + 1 }
            base.copy(budgetTokens = budgetTokens, usedTokens = usedTokens, drops = drops)
        }
    }

    /** Add the conversation messages and older tool steps a single prompt build had to cut. */
    fun recordTrim(taskId: String, droppedMessages: Int = 0, droppedSteps: Int = 0) {
        if (droppedMessages == 0 && droppedSteps == 0) return
        usage.compute(taskId) { _, prev ->
            val base = prev ?: ContextUsage.NONE
            base.copy(
                droppedMessages = base.droppedMessages + droppedMessages,
                droppedSteps = base.droppedSteps + droppedSteps,
            )
        }
    }

    /** The accumulated context usage for [taskId]; [ContextUsage.NONE] when none recorded. */
    fun usageFor(taskId: String): ContextUsage = usage[taskId] ?: ContextUsage.NONE

    /** Test-only: forget all accumulated usage. */
    fun reset() {
        usage.clear()
    }
}
