package pl.jclab.refio.core.workflow.executors

import pl.jclab.refio.core.api.routers.AgentRouter
import pl.jclab.refio.core.services.execution.unified.ExecutionEventListener
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.WorkflowIntent

/**
 * Adapter for AgentRouter step execution.
 */
class StepExecutor(
    private val agentRouter: AgentRouter
) {
    suspend fun execute(
        intent: WorkflowIntent.ExecuteStep,
        listener: ExecutionEventListener? = null,
        orchestrationEnabled: Boolean = false
    ): IntentResult {
        val response = if (orchestrationEnabled) {
            val orchestrationResponse = agentRouter.executeSubtaskStepWithOrchestration(
                intent.taskId,
                intent.subtaskId,
                listener
            )
            pl.jclab.refio.core.api.ExecuteStepResponse(
                status = orchestrationResponse.status,
                summary = orchestrationResponse.summary,
                durationMs = orchestrationResponse.durationMs,
                error = orchestrationResponse.error
            )
        } else if (listener == null) {
            agentRouter.executeSubtaskStep(intent.taskId, intent.subtaskId)
        } else {
            agentRouter.executeSubtaskStepWithListener(intent.taskId, intent.subtaskId, listener)
        }

        return IntentResult.StepResult(response)
    }
}
