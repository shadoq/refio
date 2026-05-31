package pl.jclab.refio.core.services.monitoring

import pl.jclab.refio.core.services.logging.coreLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Global metrics service tracking all operations across all sessions.
 * Platform-agnostic singleton (no IntelliJ dependencies).
 *
 * This is a core service - it does NOT depend on plugin infrastructure.
 */
object GlobalMetrics {
    private val logger = coreLogger("GlobalMetrics")

    // Cancellation flag (thread-safe)
    private val _isCancelled = AtomicBoolean(false)

    // Active agent-turn gate (thread-safe, counts nested/concurrent turns).
    // Background RAG indexing/embedding yields the single SQLite WAL writer-lock while
    // this is > 0 — see awaitAgentTurnIdle(). Concurrent RAG writes otherwise stalled
    // tool subtask-status writes ~122s (writer-lock + busy_timeout retry stacking).
    private val _activeAgentTurns = AtomicInteger(0)

    // Request counters
    private val _totalRequests = AtomicInteger(0)
    private val _successfulRequests = AtomicInteger(0)
    private val _failedRequests = AtomicInteger(0)

    // Token counters (all operations)
    private val _totalTokensIn = AtomicLong(0)
    private val _totalTokensOut = AtomicLong(0)

    // Cost tracking
    private val _totalCostUsd = AtomicLong(0) // Store as cents (x100)

    // Cache metrics
    val cacheMetrics = ConcurrentHashMap<String, CacheStats>()

    // Operation time tracking
    val operationMetrics = ConcurrentHashMap<String, OperationMetrics>()

    // Current operation tracking (backward compat — delegates to "default" agent)
    private val _currentOperation = MutableStateFlow<OperationInfo>(OperationInfo.Idle)
    val currentOperation: StateFlow<OperationInfo> = _currentOperation.asStateFlow()

    // ── Per-agent tracking (multi-agent support) ──

    private val agentMetricsMap = ConcurrentHashMap<String, AgentMetrics>()

    /**
     * Get or create per-agent metrics.
     * Multi-agent orchestrator uses this to track each agent independently.
     */
    fun forAgent(agentId: String): AgentMetrics =
        agentMetricsMap.getOrPut(agentId) { AgentMetrics(agentId) }

    /**
     * Remove agent metrics (cleanup after agent completes).
     */
    fun removeAgent(agentId: String) {
        agentMetricsMap.remove(agentId)
    }

    /**
     * Get all active agent metrics (for GUI dashboard).
     */
    fun allAgentMetrics(): Map<String, AgentMetrics> = agentMetricsMap.toMap()

    // Metrics state
    data class MetricsSnapshot(
        val totalRequests: Int,
        val successfulRequests: Int,
        val failedRequests: Int,
        val totalTokensIn: Long,
        val totalTokensOut: Long,
        val totalCostUsd: Double,
        val currentOperation: OperationInfo
    )

    private val _metrics = MutableStateFlow(
        MetricsSnapshot(
            totalRequests = 0,
            successfulRequests = 0,
            failedRequests = 0,
            totalTokensIn = 0,
            totalTokensOut = 0,
            totalCostUsd = 0.0,
            currentOperation = OperationInfo.Idle
        )
    )
    val metrics: StateFlow<MetricsSnapshot> = _metrics.asStateFlow()

    /**
     * Record a new LLM request
     */
    fun recordRequest(
        tokensIn: Int,
        tokensOut: Int,
        costUsd: Double,
        success: Boolean
    ) {
        _totalRequests.incrementAndGet()
        if (success) {
            _successfulRequests.incrementAndGet()
        } else {
            _failedRequests.incrementAndGet()
        }

        _totalTokensIn.addAndGet(tokensIn.toLong())
        _totalTokensOut.addAndGet(tokensOut.toLong())
        _totalCostUsd.addAndGet((costUsd * 100).toLong()) // Store as cents

        updateMetricsSnapshot()

        logger.debug {
            "Request recorded: tokens=$tokensIn/$tokensOut, cost=$costUsd, success=$success"
        }
    }

    /**
     * Set current operation (what core is doing right now)
     */
    fun setCurrentOperation(operation: OperationInfo) {
        _currentOperation.value = operation
        updateMetricsSnapshot()

        logger.debug { "Current operation: $operation" }
    }

    data class OperationToken(
        val previous: OperationInfo,
        val current: OperationInfo
    )

    fun beginOperation(operation: OperationInfo): OperationToken {
        val previous = _currentOperation.value
        setCurrentOperation(operation)
        return OperationToken(previous, operation)
    }

    fun endOperation(token: OperationToken) {
        if (_currentOperation.value == token.current) {
            _currentOperation.value = token.previous
            updateMetricsSnapshot()
            logger.debug { "Current operation restored: ${token.previous}" }
        }
    }

    /**
     * Clear current operation (back to idle)
     */
    fun clearCurrentOperation() {
        setCurrentOperation(OperationInfo.Idle)
    }

    /**
     * Request cancellation of current operation.
     * This flag is checked by adapters, executors, and tools.
     */
    fun requestCancellation() {
        _isCancelled.set(true)
        logger.info { "Operation cancellation requested" }
    }

    /**
     * Reset cancellation flag (call at start of new operation).
     */
    fun resetCancellation() {
        _isCancelled.set(false)
        logger.debug { "Cancellation flag reset" }
    }

    /**
     * Check if current operation is cancelled.
     * @return true if user requested cancellation
     */
    fun isCancelled(): Boolean = _isCancelled.get()

    /**
     * Mark an agent turn as active. Pairs with [endAgentTurn] in a try/finally so the
     * count stays balanced even when a turn throws. Background RAG work pauses while any
     * turn is active (see [awaitAgentTurnIdle]).
     */
    fun beginAgentTurn() {
        _activeAgentTurns.incrementAndGet()
    }

    /**
     * Mark an agent turn as finished. Guards against an unbalanced call leaving a
     * negative count (which would falsely report no turn active).
     */
    fun endAgentTurn() {
        if (_activeAgentTurns.decrementAndGet() < 0) {
            _activeAgentTurns.set(0)
        }
    }

    /** True while at least one agent turn (main or nested subagent) is running. */
    fun isAgentTurnActive(): Boolean = _activeAgentTurns.get() > 0

    /**
     * Suspend until no agent turn is active. Called by background RAG indexing/embedding
     * between work items so they yield the single SQLite WAL writer-lock to active turns.
     * RAG is a secondary feature — turns take priority. [delay] is cancellable, so a
     * cancelled indexing job exits the wait promptly.
     */
    suspend fun awaitAgentTurnIdle(pollMs: Long = 250L) {
        while (isAgentTurnActive()) {
            delay(pollMs)
        }
    }

    /**
     * Record a cache access (hit or miss) for the named cache.
     */
    fun recordCacheAccess(cacheName: String, hit: Boolean) {
        val stats = cacheMetrics.getOrPut(cacheName) { CacheStats() }
        if (hit) stats.hitCount.incrementAndGet()
        else stats.missCount.incrementAndGet()
    }

    /**
     * Update the current size of a named cache.
     */
    fun recordCacheSize(cacheName: String, size: Int) {
        val stats = cacheMetrics.getOrPut(cacheName) { CacheStats() }
        stats.size.set(size)
    }

    /**
     * Record the duration of a named operation.
     */
    fun recordOperationTime(operation: String, durationMs: Long) {
        val metrics = operationMetrics.getOrPut(operation) { OperationMetrics() }
        metrics.count.incrementAndGet()
        metrics.totalTimeMs.addAndGet(durationMs)
        metrics.maxTimeMs.updateAndGet { maxOf(it, durationMs) }
    }

    /**
     * Return a formatted summary of all performance metrics.
     */
    fun getPerformanceSummary(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Performance Summary ===")
        sb.appendLine()

        // Request metrics
        sb.appendLine("Requests: ${_totalRequests.get()} total, " +
            "${_successfulRequests.get()} success, ${_failedRequests.get()} failed")
        sb.appendLine("Tokens: ${_totalTokensIn.get()} in, ${_totalTokensOut.get()} out")
        sb.appendLine("Cost: $${_totalCostUsd.get() / 100.0}")
        sb.appendLine()

        // Cache metrics
        if (cacheMetrics.isNotEmpty()) {
            sb.appendLine("--- Cache Metrics ---")
            for ((name, stats) in cacheMetrics.entries.sortedBy { it.key }) {
                val hits = stats.hitCount.get()
                val misses = stats.missCount.get()
                val total = hits + misses
                val hitRate = if (total > 0) "%.1f%%".format(hits * 100.0 / total) else "N/A"
                sb.appendLine("  $name: size=${stats.size.get()}, hits=$hits, misses=$misses, hitRate=$hitRate")
            }
            sb.appendLine()
        }

        // Operation metrics
        if (operationMetrics.isNotEmpty()) {
            sb.appendLine("--- Operation Metrics ---")
            for ((name, metrics) in operationMetrics.entries.sortedBy { it.key }) {
                val count = metrics.count.get()
                val avgMs = if (count > 0) metrics.totalTimeMs.get() / count else 0
                sb.appendLine("  $name: count=$count, totalMs=${metrics.totalTimeMs.get()}, " +
                    "avgMs=$avgMs, maxMs=${metrics.maxTimeMs.get()}")
            }
            sb.appendLine()
        }

        return sb.toString().trimEnd()
    }

    /**
     * Reset all metrics (for testing or user request)
     */
    fun reset() {
        _totalRequests.set(0)
        _successfulRequests.set(0)
        _failedRequests.set(0)
        _totalTokensIn.set(0)
        _totalTokensOut.set(0)
        _totalCostUsd.set(0)
        cacheMetrics.clear()
        operationMetrics.clear()

        updateMetricsSnapshot()

        logger.info { "Global metrics reset" }
    }

    /**
     * Update metrics snapshot (triggers UI update)
     */
    private fun updateMetricsSnapshot() {
        _metrics.value = MetricsSnapshot(
            totalRequests = _totalRequests.get(),
            successfulRequests = _successfulRequests.get(),
            failedRequests = _failedRequests.get(),
            totalTokensIn = _totalTokensIn.get(),
            totalTokensOut = _totalTokensOut.get(),
            totalCostUsd = _totalCostUsd.get() / 100.0, // Convert cents to dollars
            currentOperation = _currentOperation.value
        )
    }
}

/**
 * Information about current operation
 */
sealed class OperationInfo {
    object Idle : OperationInfo() {
        override fun toString() = "Idle"
    }

    data class ChatRequest(val model: String) : OperationInfo() {
        override fun toString() = "Chat: $model"
    }

    data class SummarizingConversation(val taskId: String) : OperationInfo() {
        override fun toString() = "Summarizing: $taskId"
    }

    data class PlanningRequest(val model: String) : OperationInfo() {
        override fun toString() = "Planning: $model"
    }

    data class PlanningStep(val stepNumber: Int, val totalSteps: Int, val description: String) : OperationInfo() {
        override fun toString() = "Planning step $stepNumber/$totalSteps"
    }

    data class ExecutingStep(val stepNumber: Int, val totalSteps: Int, val description: String) : OperationInfo() {
        override fun toString() = "Executing step $stepNumber/$totalSteps"
    }

    data class ExecutingTool(val toolName: String, val stepNumber: Int) : OperationInfo() {
        override fun toString() = "Tool: $toolName (step $stepNumber)"
    }

    data class StepPlanning(val stepNumber: Int, val totalSteps: Int) : OperationInfo() {
        override fun toString() = "Step planning $stepNumber/$totalSteps"
    }

    data class StepExecuting(val stepNumber: Int, val totalSteps: Int) : OperationInfo() {
        override fun toString() = "Step executing $stepNumber/$totalSteps"
    }

    data class StepSummarizing(val stepNumber: Int, val totalSteps: Int) : OperationInfo() {
        override fun toString() = "Step summarizing $stepNumber/$totalSteps"
    }

    data class StepReasoning(val stepNumber: Int, val totalSteps: Int) : OperationInfo() {
        override fun toString() = "Step reasoning $stepNumber/$totalSteps"
    }

    data class Orchestration(val phase: String, val stepNumber: Int? = null, val totalSteps: Int? = null) :
        OperationInfo() {
        override fun toString(): String {
            val stepInfo = if (stepNumber != null && totalSteps != null) {
                " $stepNumber/$totalSteps"
            } else ""
            return "Orchestration: $phase$stepInfo"
        }
    }

    data class SavingToDatabase(val operation: String) : OperationInfo() {
        override fun toString() = "DB: $operation"
    }

    data class LoadingFromDatabase(val operation: String) : OperationInfo() {
        override fun toString() = "DB: Loading $operation"
    }

    data class AutoExecution(val taskId: String) : OperationInfo() {
        override fun toString() = "Auto Execution"
    }

    data class SubagentRequest(val subagent: String) : OperationInfo() {
        override fun toString() = "Subagent: $subagent"
    }

    // AgentTurnLoop operations
    data class TurnLoop(val iteration: Int, val maxIterations: Int, val mode: String) : OperationInfo() {
        override fun toString() = "Turn $iteration/$maxIterations ($mode)"
    }

    data class TurnToolExecution(val toolName: String, val iteration: Int) : OperationInfo() {
        override fun toString() = "Exec: $iteration ($toolName)"
    }

    data class TurnToolSummarization(val toolName: String, val iteration: Int) : OperationInfo() {
        override fun toString() = "Summary: $iteration ($toolName)"
    }

    data class TurnBuildingPrompt(val iteration: Int, val historySize: Int) : OperationInfo() {
        override fun toString() = "Prompt: $iteration ($historySize messages)"
    }

    data class TurnLLMCall(val iteration: Int, val mode: String) : OperationInfo() {
        override fun toString() = "Loop: $iteration ($mode)"
    }
}

/**
 * Cache statistics for a named cache.
 */
data class CacheStats(
    val size: AtomicInteger = AtomicInteger(0),
    val hitCount: AtomicLong = AtomicLong(0),
    val missCount: AtomicLong = AtomicLong(0)
)

/**
 * Timing metrics for a named operation.
 */
data class OperationMetrics(
    val count: AtomicLong = AtomicLong(0),
    val totalTimeMs: AtomicLong = AtomicLong(0),
    val maxTimeMs: AtomicLong = AtomicLong(0)
)

/**
 * Per-agent metrics for multi-agent execution.
 * Each agent has its own operation state and cancellation flag,
 * preventing one agent's state from overwriting another's.
 */
class AgentMetrics(val agentId: String) {
    private val _currentOperation = MutableStateFlow<OperationInfo>(OperationInfo.Idle)
    val currentOperation: StateFlow<OperationInfo> = _currentOperation.asStateFlow()

    private val _isCancelled = AtomicBoolean(false)

    private val _tokensIn = AtomicLong(0)
    private val _tokensOut = AtomicLong(0)
    private val _costUsd = AtomicLong(0) // cents

    fun setCurrentOperation(op: OperationInfo) { _currentOperation.value = op }
    fun clearCurrentOperation() { _currentOperation.value = OperationInfo.Idle }
    fun requestCancellation() { _isCancelled.set(true) }
    fun resetCancellation() { _isCancelled.set(false) }
    fun isCancelled(): Boolean = _isCancelled.get()

    fun recordTokens(tokensIn: Int, tokensOut: Int, costUsd: Double) {
        _tokensIn.addAndGet(tokensIn.toLong())
        _tokensOut.addAndGet(tokensOut.toLong())
        _costUsd.addAndGet((costUsd * 100).toLong())
    }

    val totalTokensIn: Long get() = _tokensIn.get()
    val totalTokensOut: Long get() = _tokensOut.get()
    val totalCostUsd: Double get() = _costUsd.get() / 100.0
}
