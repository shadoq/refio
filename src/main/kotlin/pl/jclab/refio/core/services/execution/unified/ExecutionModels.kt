package pl.jclab.refio.core.services.execution.unified

import pl.jclab.refio.core.api.PlanDecisionInfo
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.services.StepExecutionResult
import pl.jclab.refio.core.services.orchestration.ReflectionDecision

/**
 * Step plan returned by prepare phase.
 */
data class StepPlan(
    val subtaskId: String,
    val description: String,
    val tools: List<ToolPlan>,
    val estimatedDurationMs: Int? = null,
    val planDecision: PlanDecisionInfo? = null
)

data class ToolPlan(
    val name: String,
    val params: Map<String, Any>
)

/**
 * Step result returned by execute phase.
 */
data class StepResult(
    val subtaskId: String,
    val status: String, // "success" or "failed"
    val summary: String,
    val durationMs: Int,
    val error: String? = null,
    val executionResult: StepExecutionResult? = null
)

/**
 * Overall execution statistics.
 */
data class ExecutionStats(
    val stepsExecuted: Int,
    val stepsFailed: Int,
    val stepsSkipped: Int = 0,
    val durationMs: Long,
    val reflections: List<ReflectionDecision> = emptyList()
)

/**
 * Execution result wrapper.
 */
sealed class ExecutionResult {
    data class Success(val stats: ExecutionStats) : ExecutionResult()
    data class Failure(val error: Throwable, val stats: ExecutionStats? = null) : ExecutionResult()

    companion object {
        fun success(stats: ExecutionStats) = Success(stats)
        fun failure(error: Throwable, stats: ExecutionStats? = null) = Failure(error, stats)
    }
}
