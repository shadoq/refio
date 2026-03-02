package pl.jclab.refio.core.services.monitoring

import pl.jclab.refio.core.services.logging.coreLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // Request counters
    private val _totalRequests = AtomicInteger(0)
    private val _successfulRequests = AtomicInteger(0)
    private val _failedRequests = AtomicInteger(0)

    // Token counters (all operations)
    private val _totalTokensIn = AtomicLong(0)
    private val _totalTokensOut = AtomicLong(0)

    // Cost tracking
    private val _totalCostUsd = AtomicLong(0) // Store as cents (x100)

    // Current operation tracking
    private val _currentOperation = MutableStateFlow<OperationInfo>(OperationInfo.Idle)
    val currentOperation: StateFlow<OperationInfo> = _currentOperation.asStateFlow()

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
     * Reset all metrics (for testing or user request)
     */
    fun reset() {
        _totalRequests.set(0)
        _successfulRequests.set(0)
        _failedRequests.set(0)
        _totalTokensIn.set(0)
        _totalTokensOut.set(0)
        _totalCostUsd.set(0)

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
