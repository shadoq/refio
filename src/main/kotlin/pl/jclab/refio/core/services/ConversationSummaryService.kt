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
import pl.jclab.refio.services.logging.dualLogger

private const val CONVERSATION_SUMMARY_METADATA_TYPE = "conversation_summary"
private val logger = dualLogger("ConversationSummaryService")

class ConversationSummaryService(
    private val llmClient: LLMClient,
    private val promptsService: PromptsService,
    private val configService: ConfigService,
    private val chatMessageRepository: ChatMessageRepository
) {
    fun shouldSummarize(messages: List<ChatMessage>, maxTokens: Int): Boolean {
        if (messages.isEmpty() || maxTokens <= 0) return false
        val totalTokens = messages.sumOf { ContextTokenEstimator.estimateTokens(it.content) }
        return totalTokens > (maxTokens * 0.7).toInt()
    }

    suspend fun ensureSummaryIfNeeded(
        taskId: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
        keepRecent: Int = 6
    ): List<ChatMessage> {
        if (messages.isEmpty() || maxTokens <= 0) return messages

        val lastSummaryIndex = messages.indexOfLast { isConversationSummary(it) }
        val messagesSinceSummary = if (lastSummaryIndex >= 0) {
            messages.drop(lastSummaryIndex + 1)
        } else {
            messages
        }

        if (messagesSinceSummary.size <= keepRecent) return messages
        if (!shouldSummarize(messagesSinceSummary, maxTokens)) return messages

        val toSummarize = messagesSinceSummary.dropLast(keepRecent)
        if (toSummarize.isEmpty()) return messages

        logger.info { "[CONVERSATION_SUMMARY] Summarizing ${toSummarize.size} messages for task=$taskId" }

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
            append(response.content.trim())
        }

        chatMessageRepository.create(
            taskId = taskId,
            role = MessageRole.SYSTEM,
            content = summaryContent,
            metadata = metadataJson,
            tokensIn = response.usage.inputTokens,
            tokensOut = response.usage.outputTokens,
            cost = response.cost
        )

        return chatMessageRepository.findByTaskId(taskId)
    }

    private fun isConversationSummary(message: ChatMessage): Boolean {
        val metadata = message.metadata ?: return false
        return metadata.contains("\"type\":\"$CONVERSATION_SUMMARY_METADATA_TYPE\"")
    }
}
