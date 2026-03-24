package pl.jclab.refio.cli.ui

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.jclab.refio.core.workflow.WorkflowEventListener
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.WorkflowIntent
import java.util.UUID

class ComposeWorkflowListener(
    private val agentId: String,
    private val agentName: String,
    private val agentColor: Color,
    private val messagesState: MutableStateFlow<List<UIChatMessage>>,
    private val streamingState: MutableStateFlow<Boolean>,
    private val scope: CoroutineScope
) : WorkflowEventListener {

    private var accumulatedContent = StringBuilder()

    override fun onChatStarted() {
        startStreaming("")
    }

    override fun onPlanningStarted() {
        startStreaming("Planning...")
    }

    override fun onSubagentStarted(subagentName: String) {
        startStreaming("[$subagentName] ...")
    }

    override fun onStreamChunk(chunk: String) {
        accumulatedContent.append(chunk)
        scope.launch {
            val streamId = "$agentId-stream"
            val content = accumulatedContent.toString()
            messagesState.update { messages ->
                val existing = messages.indexOfLast { it.id == streamId }
                val msg = UIChatMessage(
                    id = streamId,
                    timestamp = System.currentTimeMillis(),
                    role = "assistant",
                    content = content,
                    agentId = agentId,
                    agentName = agentName,
                    agentColor = agentColor,
                    isStreaming = true
                )
                if (existing >= 0) {
                    messages.toMutableList().also { it[existing] = msg }
                } else {
                    messages + msg
                }
            }
        }
    }

    override fun onStreamComplete(content: String) {
        scope.launch {
            messagesState.update { messages ->
                messages.map {
                    if (it.id == "$agentId-stream") it.copy(
                        content = content,
                        isStreaming = false,
                        id = UUID.randomUUID().toString()
                    ) else it
                }
            }
            streamingState.value = false
            accumulatedContent.clear()
        }
    }

    override fun onWorkflowComplete(result: IntentResult) {
        scope.launch {
            streamingState.value = false
        }
    }

    override fun onWorkflowError(error: Exception) {
        scope.launch {
            streamingState.value = false
            messagesState.update { messages ->
                messages + UIChatMessage(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    role = "system",
                    content = "Error: ${error.message}",
                    messageType = MessageType.AGENT_FAILED
                )
            }
        }
    }

    private fun startStreaming(initialContent: String) {
        accumulatedContent.clear()
        if (initialContent.isNotEmpty()) {
            accumulatedContent.append(initialContent)
        }
        scope.launch {
            streamingState.value = true
        }
    }
}
