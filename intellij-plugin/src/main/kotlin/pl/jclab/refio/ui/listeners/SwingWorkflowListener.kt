package pl.jclab.refio.ui.listeners

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.core.workflow.WorkflowEventListener
import pl.jclab.refio.core.session.SessionStateManager
import pl.jclab.refio.core.logging.dualLogger
import java.util.UUID

private val logger = dualLogger("SwingWorkflowListener")

class SwingWorkflowListener(
    private val taskId: String,
    private val stateManager: SessionStateManager,
    private val scope: CoroutineScope,
    private val streamingEnabled: Boolean
) : WorkflowEventListener {

    private var messageId: String? = null
    private var lastUiUpdate = 0L
    private var formatter: ((String) -> String)? = null

    override fun onChatStarted() {
        startStreamingMessage("", "assistant") { it }
    }

    override fun onPlanningStarted() {
        startStreamingMessage("Planning...", "assistant") { accumulated ->
            "Planning...\n\n```json\n$accumulated\n```"
        }
    }

    override fun onSubagentStarted(subagentName: String) {
        startStreamingMessage("[$subagentName] ...", "assistant") { accumulated ->
            "[$subagentName]\n\n$accumulated"
        }
    }

    override fun onStreamChunk(chunk: String) {
        if (!streamingEnabled) return
        val currentId = messageId ?: return
        val now = System.currentTimeMillis()
        if (now - lastUiUpdate < 500L) return
        lastUiUpdate = now

        val format = formatter ?: { it }
        scope.launch(Dispatchers.IO) {
            stateManager.updateMessages { messages ->
                messages.map { msg ->
                    if (msg.id == currentId) {
                        msg.copy(content = format(chunk), lastChunkAt = now)
                    } else {
                        msg
                    }
                }
            }
        }
    }

    override fun onStreamComplete(content: String) {
        val currentId = messageId ?: return
        val format = formatter ?: { it }
        scope.launch(Dispatchers.IO) {
            stateManager.updateMessages { messages ->
                messages.map { msg ->
                    if (msg.id == currentId) {
                        msg.copy(
                            content = format(content),
                            isStreaming = false,
                            lastChunkAt = System.currentTimeMillis()
                        )
                    } else {
                        msg
                    }
                }
            }
        }
    }

    private fun startStreamingMessage(
        initialContent: String,
        role: String,
        format: (String) -> String
    ) {
        formatter = format

        val id = UUID.randomUUID().toString()
        messageId = id

        val message = Message(
            id = id,
            taskId = taskId,
            role = role,
            content = initialContent,
            isStreaming = streamingEnabled,
            createdAt = System.currentTimeMillis()
        )

        scope.launch(Dispatchers.IO) {
            stateManager.appendMessage(message)
        }
    }
}

