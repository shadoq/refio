package pl.jclab.refio.core.workflow.executors

import pl.jclab.refio.core.api.PlanningRequest
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.services.PlanningService
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.WorkflowIntent

/**
 * Adapter for PlanningService plan creation.
 */
class PlanExecutor(
    private val planningService: PlanningService
) {
    suspend fun execute(
        intent: WorkflowIntent.Plan,
        stream: Boolean,
        onChunk: StreamCallback?
    ): IntentResult {
        val request = PlanningRequest(
            input = intent.input,
            contextRefs = intent.contextRefs,
            model = intent.model,
            provider = intent.provider,
            interactive = intent.interactive
        )

        val response = planningService.createPlan(intent.taskId, request, stream, onChunk)
        return IntentResult.PlanResult(response)
    }
}
