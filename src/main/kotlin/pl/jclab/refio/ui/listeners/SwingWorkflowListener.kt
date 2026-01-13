package pl.jclab.refio.ui.listeners

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.core.workflow.IntentClassificationResult
import pl.jclab.refio.core.workflow.WorkflowEventListener
import pl.jclab.refio.services.session.SessionStateManager
import pl.jclab.refio.services.logging.dualLogger
import java.util.UUID
import javax.swing.SwingUtilities

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

    private var classificationMessageId: String? = null

    override fun onIntentClassificationStarted(model: String, mode: String) {
        logger.info { "[INTENT_UI] onIntentClassificationStarted called: model=$model, mode=$mode" }
        val id = UUID.randomUUID().toString()
        classificationMessageId = id

        val message = Message(
            id = id,
            taskId = taskId,
            role = "system",
            content = "🧠 Classifying intent... (model: $model, mode: $mode)",
            isStreaming = true,
            createdAt = System.currentTimeMillis()
        )

        logger.info { "[INTENT_UI] Created intent classification message: id=$id, taskId=$taskId" }
        SwingUtilities.invokeLater {
            logger.info { "[INTENT_UI] EDT: appending intent classification start message" }
            scope.launch(Dispatchers.IO) {
                stateManager.appendMessage(message)
                logger.info { "[INTENT_UI] IO: appended intent classification start message" }
            }
        }
    }

    override fun onIntentClassificationResult(result: IntentClassificationResult) {
        logger.info { "[INTENT_UI] onIntentClassificationResult called: result=${result::class.simpleName}" }
        val currentId = classificationMessageId ?: run {
            logger.warn { "[INTENT_UI] WARNING: classificationMessageId is null, cannot update message" }
            return
        }

        val decisionText = when (result) {
            is IntentClassificationResult.ChatResponse -> "💬 CHAT_RESPONSE - direct answer"
            is IntentClassificationResult.ClarificationNeeded -> "❓ CLARIFICATION_NEEDED - need clarification"
            is IntentClassificationResult.SingleTool -> "🔧 SINGLE_TOOL - execute: ${result.toolName}"
            is IntentClassificationResult.MultiStepPlan -> "📋 MULTI_STEP_PLAN - create execution plan"
        }

        val content = """🧠 Intent classification complete
**Decision:** $decisionText
**Reasoning:** ${result.reasoning}"""

        logger.info { "[INTENT_UI] Updating classification message: id=$currentId, decision=$decisionText" }
        SwingUtilities.invokeLater {
            logger.info { "[INTENT_UI] EDT: updating intent classification result message" }
            scope.launch(Dispatchers.IO) {
                stateManager.updateMessages { messages ->
                    val updated = messages.map { msg ->
                        if (msg.id == currentId) {
                            msg.copy(
                                content = content,
                                isStreaming = false,
                                lastChunkAt = System.currentTimeMillis()
                            )
                        } else {
                            msg
                        }
                    }
                    logger.info { "[INTENT_UI] IO: updated ${updated.count { it.id == currentId }} messages" }
                    updated
                }
            }
        }
        classificationMessageId = null
    }

    override fun onStreamChunk(chunk: String) {
        if (!streamingEnabled) return
        val currentId = messageId ?: return
        val now = System.currentTimeMillis()
        if (now - lastUiUpdate < 500L) return
        lastUiUpdate = now

        val format = formatter ?: { it }
        SwingUtilities.invokeLater {
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
    }

    override fun onStreamComplete(content: String) {
        val currentId = messageId ?: return
        val format = formatter ?: { it }
        SwingUtilities.invokeLater {
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

        SwingUtilities.invokeLater {
            scope.launch(Dispatchers.IO) {
                stateManager.appendMessage(message)
            }
        }
    }
}
