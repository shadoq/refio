package pl.jclab.refio.core.workflow.executors

import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.WorkflowIntent

/**
 * Adapter for SubagentRouter invocation.
 */
class SubagentExecutor(
    private val subagentRouter: SubagentRouter
) {
    suspend fun execute(
        intent: WorkflowIntent.Subagent,
        stream: Boolean,
        onChunk: StreamCallback?
    ): IntentResult {
        val parentModel = intent.model?.let { model ->
            intent.provider?.let { provider -> "$provider/$model" }
        }

        val response = subagentRouter.invoke(
            taskId = intent.taskId,
            name = intent.name,
            prompt = intent.prompt,
            contextRefs = intent.contextRefs,
            stream = stream,
            onChunk = onChunk,
            parentModel = parentModel
        )

        return IntentResult.SubagentResult(response)
    }
}
