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
        return if (lastSummaryIndex >= 2) {
            messages.drop(lastSummaryIndex - 1)
        } else {
            messages
        }
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
        toolContentResolver: (ChatMessage) -> String
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
                LLMMessageMapper.fromToolResult(msg, summarized)
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
