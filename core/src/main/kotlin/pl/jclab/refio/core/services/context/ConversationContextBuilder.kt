package pl.jclab.refio.core.services.context

import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMMessageMapper

/**
 * Stateless helper that slices, filters and converts persisted [ChatMessage] lists
 * into [LLMMessage] sequences suitable for an LLM turn.
 *
 * Extracted from [pl.jclab.refio.core.services.ContextService].
 */
class ConversationContextBuilder {

    companion object {
        const val CONVERSATION_SUMMARY_METADATA_TYPE = "conversation_summary"
    }

    // ── public API ────────────────────────────────────────────────────────

    /**
     * Filter conversation history to keep only meaningful exchanges.
     * Removes system messages, tool usage notifications, and very short messages.
     * Based on Python context_service.py lines 966-990
     */
    fun filterMeaningfulConversation(
        messages: List<ChatMessage>
    ): List<ChatMessage> {
        return messages.filter { msg ->
            when (msg.role) {
                MessageRole.USER -> msg.content.isNotBlank()
                MessageRole.ASSISTANT -> {
                    msg.content.isNotBlank() &&
                        msg.toolCalls.isNullOrEmpty() &&
                        !looksLikeToolEnvelope(msg.content)
                }
                MessageRole.TOOL -> {
                    msg.content.isNotBlank() || !msg.rawOutput.isNullOrBlank()
                }
                MessageRole.SYSTEM -> {
                    isConversationSummary(msg) ||
                        msg.metadata == "compaction" ||
                        msg.content.contains("<conversation_summary>") ||
                        msg.content.contains("<parent_working_memory>")
                }
            }
        }
    }

    fun sliceConversationHistoryFromLastSummary(
        messages: List<ChatMessage>
    ): List<ChatMessage> {
        val lastSummaryIndex = messages.indexOfLast { isConversationSummary(it) }
        if (lastSummaryIndex < 2) return messages

        val tail = messages.drop(lastSummaryIndex - 1)
        val droppedHead = messages.take(lastSummaryIndex - 1)

        // Always preserve the very first message (usually the user's opening
        // request) and every USER message that appeared before the summary —
        // they may carry instructions, constraints, or facts the model still
        // needs, and they are small enough that duplicating them next to the
        // summary is cheap.
        val preservedHead = droppedHead.filterIndexed { index, msg ->
            index == 0 || msg.role == MessageRole.USER
        }

        return preservedHead + tail
    }

    /**
     * Build a lookup of tool call id → tool name by scanning ASSISTANT messages.
     * Used so TOOL result messages can be rendered with the originating tool's
     * name (e.g. `[Tool result: run_code id: abc]`) instead of a bare id.
     */
    fun buildToolNameByCallId(messages: List<ChatMessage>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (msg in messages) {
            if (msg.role != MessageRole.ASSISTANT) continue
            val calls = msg.toolCalls ?: continue
            for (call in calls) {
                if (call.id.isNotBlank() && call.name.isNotBlank()) {
                    map[call.id] = call.name
                }
            }
        }
        return map
    }

    fun isConversationSummary(message: ChatMessage): Boolean {
        val metadata = message.metadata ?: return false
        return metadata.contains("\"type\":\"$CONVERSATION_SUMMARY_METADATA_TYPE\"")
    }

    fun looksLikeToolEnvelope(content: String): Boolean {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false

        return trimmed.contains("\"actions\"") ||
            trimmed.contains("\"tool_calls\"") ||
            trimmed.contains("\"subtasks\"") ||
            trimmed.contains("\"intent\"")
    }

    /**
     * Convert ChatMessage to LLMMessage for AgentTurnLoop.
     * Tool messages always use summarized/compact content.
     *
     * @param toolContentResolver resolves the conversation-ready content for TOOL messages
     *   (kept in ContextService because it is also used outside this builder).
     */
    fun convertChatMessageToLLMMessage(
        msg: ChatMessage,
        toolContentResolver: (ChatMessage) -> String,
        toolNameByCallId: Map<String, String> = emptyMap()
    ): LLMMessage? {
        return when (msg.role) {
            MessageRole.USER -> LLMMessage(
                role = "user",
                content = msg.content
            )

            MessageRole.ASSISTANT -> {
                // If assistant has tool calls, append them to content
                val toolCallsText = if (!msg.toolCalls.isNullOrEmpty()) {
                    msg.toolCalls.joinToString("\n") { tc ->
                        "TOOL_CALL: ${tc.name}\nARGUMENTS: ${tc.arguments}"
                    }
                } else null

                val content = buildList {
                    if (msg.content.isNotBlank()) add(msg.content)
                    if (toolCallsText != null) add("\n\nTool calls:\n$toolCallsText")
                }.joinToString("")

                if (content.isNotBlank()) {
                    LLMMessage(role = "assistant", content = content)
                } else null
            }

            MessageRole.TOOL -> {
                val summarized = toolContentResolver(msg)
                val toolName = msg.toolCallId?.let { toolNameByCallId[it] }
                LLMMessageMapper.fromToolResult(msg, summarized, toolName)
            }

            MessageRole.SYSTEM -> {
                val isSummaryMessage = isConversationSummary(msg) ||
                    msg.metadata == "compaction" ||
                    msg.content.contains("<conversation_summary>")

                if (isSummaryMessage) {
                    LLMMessage(
                        role = "user",
                        content = "[Conversation summary context]\n${msg.content}"
                    )
                } else {
                    LLMMessage(
                        role = "system",
                        content = msg.content
                    )
                }
            }
        }
    }
}
