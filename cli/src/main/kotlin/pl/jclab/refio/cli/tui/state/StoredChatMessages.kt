package pl.jclab.refio.cli.tui.state

import pl.jclab.refio.core.api.MessageResponse
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.services.turn.TurnJsonUtils

/**
 * Maps messages read from the database to chat messages. Tool calls come out in the same shape the live
 * listener builds ([TranscriptBuilder.toolCallMessage]): one message per call taken from the assistant
 * message's tool call list, and the stored result as a second message with the same call id, which
 * [TranscriptBuilder] pairs with the call. The assistant's own text becomes an ordinary message.
 */
object StoredChatMessages {

    /** Metadata key the core sets on an assistant message whose answer hit the output limit. */
    const val META_STOP_REASON = "stop_reason"

    fun toChatMessages(stored: List<MessageResponse>, toolDurations: Map<String, Long> = emptyMap()): List<TuiChatMessage> =
        stored.flatMap { msg -> map(msg, toolDurations) }

    private fun map(msg: MessageResponse, toolDurations: Map<String, Long>): List<TuiChatMessage> {
        if (msg.role == "tool") {
            val result = TranscriptBuilder.toolResultMessage(msg.id, msg.toolCallId, msg.content, msg.createdAt, msg.agentName)
            val duration = msg.toolCallId?.let { toolDurations[it] } ?: return listOf(result)
            return listOf(result.copy(metadata = result.metadata + (TranscriptBuilder.META_DURATION_MS to duration)))
        }
        val calls = ToolCallData.fromJsonList(msg.toolCallsJson).orEmpty()
        val text = textMessage(msg).takeIf { calls.isEmpty() || it.content.isNotBlank() }
        val callMessages = calls.mapIndexed { i, call ->
            TranscriptBuilder.toolCallMessage(
                id = "${msg.id}-call-$i",
                toolCallId = call.id,
                toolName = call.name,
                argumentsJson = call.arguments,
                timestamp = msg.createdAt,
                running = false,
                agentName = msg.agentName,
            )
        }
        return listOfNotNull(text) + callMessages
    }

    private fun textMessage(msg: MessageResponse): TuiChatMessage {
        val meta = msg.metadata?.let { TurnJsonUtils.parseJsonToMap(it) }.orEmpty()
        return TuiChatMessage(
            id = msg.id,
            timestamp = msg.createdAt,
            role = msg.role,
            content = msg.content,
            agentName = msg.agentName,
            tokensIn = msg.tokensIn ?: 0,
            tokensOut = msg.tokensOut ?: 0,
            costUsd = msg.cost ?: 0.0,
            messageType = messageType(msg.metadata),
            metadata = meta.filterKeys { it == META_STOP_REASON },
        )
    }

    private fun messageType(metadata: String?): TuiMessageType = when {
        metadata == null -> TuiMessageType.TEXT
        metadata.contains("\"orchestrator_question\"") -> TuiMessageType.ORCHESTRATOR_QUESTION
        metadata.contains("\"execution_summary\"") -> TuiMessageType.EXECUTION_SUMMARY
        metadata.contains("\"plan\"") -> TuiMessageType.PLAN
        else -> TuiMessageType.TEXT
    }
}
