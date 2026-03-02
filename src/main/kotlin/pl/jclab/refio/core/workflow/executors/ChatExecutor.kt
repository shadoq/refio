package pl.jclab.refio.core.workflow.executors

import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.models.api.ChatRequest
import pl.jclab.refio.core.models.api.LLMParams
import pl.jclab.refio.core.services.ChatService
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.WorkflowIntent

/**
 * Adapter for ChatService chat execution.
 */
class ChatExecutor(
    private val chatService: ChatService
) {
    suspend fun execute(
        intent: WorkflowIntent.Chat,
        stream: Boolean,
        onChunk: StreamCallback?
    ): IntentResult {
        val request = ChatRequest(
            taskId = intent.taskId,
            mode = TaskMode.CHAT,
            input = intent.input,
            contextRefs = intent.contextRefs,
            params = LLMParams(
                model = intent.model,
                provider = intent.provider
            )
        )

        val response = chatService.chat(request, stream, onChunk)
        return IntentResult.ChatResult(response)
    }
}
