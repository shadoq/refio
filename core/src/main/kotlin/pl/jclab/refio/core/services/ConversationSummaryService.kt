package pl.jclab.refio.core.services

import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.PromptType
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.services.context.ContextTokenEstimator
import pl.jclab.refio.core.utils.GsonInstance
import pl.jclab.refio.core.logging.dualLogger

private const val CONVERSATION_SUMMARY_METADATA_TYPE = "conversation_summary"
private val logger = dualLogger("ConversationSummaryService")

class ConversationSummaryService(
    private val llmClient: LLMClient,
    private val promptsService: PromptsService,
    private val configService: ConfigService,
    private val chatMessageRepository: ChatMessageRepository
) {
    companion object {
        private const val FALLBACK_SUMMARY_MAX_CHARS = 2_000
        private const val SUMMARY_THRESHOLD_RATIO = 0.85
        private const val SUMMARY_TARGET_RATIO = 0.50
        private const val MIN_KEEP_RECENT_MESSAGES = 2
    }

    /**
     * @param contentResolver maps a ChatMessage to the string that will actually appear in the
     *   LLM prompt. Default uses `msg.content` (raw stored value), but callers that compress
     *   tool results before sending — see ContextService.resolveToolConversationContent — must
     *   pass a resolver so the trigger reflects realistic prompt size, not raw DB content.
     *   Without this a 95k-char read_file dump in TOOL.content counts as ~24k tokens even
     *   though only ~256 tokens (1024-char truncation) reach the LLM.
     */
    fun shouldSummarize(
        messages: List<ChatMessage>,
        maxTokens: Int,
        contentResolver: (ChatMessage) -> String = { it.content }
    ): Boolean {
        if (messages.isEmpty() || maxTokens <= 0) return false
        val totalTokens = messages.sumOf { ContextTokenEstimator.estimateTokens(contentResolver(it)) }
        return totalTokens > (maxTokens * SUMMARY_THRESHOLD_RATIO).toInt()
    }

    suspend fun ensureSummaryIfNeeded(
        taskId: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
        contentResolver: (ChatMessage) -> String = { it.content },
        agentInstanceId: String? = null
    ): List<ChatMessage> {
        if (messages.isEmpty() || maxTokens <= 0) return messages

        // Only summarize messages that came AFTER the most recent summary —
        // never re-summarize a summary, so we don't lose information through
        // chained compressions.
        val lastSummaryIndex = messages.indexOfLast { isConversationSummary(it) }
        val messagesSinceSummary = if (lastSummaryIndex >= 0) {
            messages.drop(lastSummaryIndex + 1)
        } else {
            messages
        }

        // Trigger is purely budget-based: only when the uncompressed tail
        // exceeds the configured ratio of the conversation budget. Tokens are
        // measured on the contentResolver output, not raw msg.content (see KDoc).
        if (!shouldSummarize(messagesSinceSummary, maxTokens, contentResolver)) return messages

        // Walk oldest-first and collect just enough messages to bring the
        // remaining tail below SUMMARY_TARGET_RATIO of the budget. Always
        // preserve at least MIN_KEEP_RECENT_MESSAGES at the end so the model
        // still sees fresh context (current user query, latest tool result).
        val tokensPerMessage = messagesSinceSummary.map { ContextTokenEstimator.estimateTokens(contentResolver(it)) }
        val totalTokens = tokensPerMessage.sum()
        val target = (maxTokens * SUMMARY_TARGET_RATIO).toInt().coerceAtLeast(1)
        val tokensToReduce = (totalTokens - target).coerceAtLeast(1)

        val maxSummarizable = (messagesSinceSummary.size - MIN_KEEP_RECENT_MESSAGES).coerceAtLeast(0)
        if (maxSummarizable == 0) return messages

        var accumulated = 0
        var summarizeCount = 0
        for (i in 0 until maxSummarizable) {
            if (accumulated >= tokensToReduce) break
            accumulated += tokensPerMessage[i]
            summarizeCount++
        }

        if (summarizeCount == 0) return messages

        val toSummarize = messagesSinceSummary.take(summarizeCount)

        logger.info {
            "[CONVERSATION_SUMMARY] Summarizing ${toSummarize.size} messages " +
                "(~$accumulated tokens) for task=$taskId, budget=$maxTokens, " +
                "tail=$totalTokens, target=$target"
        }

        val conversationText = buildString {
            toSummarize.forEach { msg ->
                appendLine("${msg.role.name.uppercase()}: ${msg.content}")
                appendLine()
            }
        }

        val summaryPrompt = promptsService.getSystemPrompt(
            type = PromptType.SYSTEM_CONVERSATION_SUMMARY,
            variables = mapOf("conversation" to conversationText)
        )

        val (model, provider) = configService.getModel(
            operation = ModelOperation.WEAK,
            taskId = taskId
        )

        val response = llmClient.complete(
            provider = provider,
            model = model,
            messages = listOf(LLMMessage(role = "user", content = summaryPrompt)),
            temperature = 0.3,
            maxTokens = 500,
            source = "ConversationSummaryService",
            taskId = taskId,
            subtaskId = null
        )

        val summaryBody = response.content.trim().ifBlank {
            response.thinking?.trim().takeIf { !it.isNullOrBlank() } ?: buildDeterministicFallback(toSummarize)
        }

        val summaryIndex = messages.count { isConversationSummary(it) } + 1
        val metadataJson = GsonInstance.gson.toJson(
            mapOf(
                "type" to CONVERSATION_SUMMARY_METADATA_TYPE,
                "summarized_count" to toSummarize.size,
                "summary_index" to summaryIndex,
                "timestamp" to System.currentTimeMillis(),
                "first_message_id" to toSummarize.first().id,
                "last_message_id" to toSummarize.last().id
            )
        )

        val summaryContent = buildString {
            append("**Conversation summary (${toSummarize.size} messages):**\n\n")
            append(summaryBody)
        }

        // Tag the summary with the same thread it summarizes, so a subagent's summary stays in the
        // subagent's thread instead of surfacing in the parent conversation.
        chatMessageRepository.create(
            taskId = taskId,
            role = MessageRole.SYSTEM,
            content = summaryContent,
            metadata = metadataJson,
            tokensIn = response.usage.inputTokens,
            tokensOut = response.usage.outputTokens,
            cost = response.cost,
            agentInstanceId = agentInstanceId
        )

        return chatMessageRepository.findHistoryForInvocation(taskId, agentInstanceId)
    }

    private fun isConversationSummary(message: ChatMessage): Boolean {
        val metadata = message.metadata ?: return false
        return metadata.contains("\"type\":\"$CONVERSATION_SUMMARY_METADATA_TYPE\"")
    }

    private fun buildDeterministicFallback(messages: List<ChatMessage>): String {
        val lines = messages.mapNotNull { msg ->
            val body = msg.content.trim().ifBlank { return@mapNotNull null }
            val normalized = body.replace(Regex("\\s+"), " ")
            // Per-message head+tail rather than head-only `.take(220)`. The
            // last sentence of an assistant message is often the conclusion
            // / decision / final answer — losing it to a 220-char prefix cap
            // turned this fallback into a confidence-laundering machine.
            val clipped = pl.jclab.refio.core.services.context.ToolResultCompression
                .headTailTruncate(normalized, 220)
            "${msg.role.name.uppercase()}: $clipped"
        }

        if (lines.isEmpty()) {
            return "No stable summary could be generated."
        }

        // Final-pass head+tail on the joined fallback so a 2000-char cap can't
        // chop the most recent (and usually most relevant) messages off the end.
        return pl.jclab.refio.core.services.context.ToolResultCompression
            .headTailTruncate(lines.joinToString("\n"), FALLBACK_SUMMARY_MAX_CHARS)
    }
}
