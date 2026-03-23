package pl.jclab.refio.core.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("ConversationCompactor")

/**
 * Compacts conversation history when context window approaches limit.
 *
 * Inspired by Codex CLI auto_compact and Claude Code reminder injection.
 * Uses weak model to generate concise summaries of older messages.
 *
 * Strategy:
 * - Keep last N messages raw (for context continuity)
 * - Summarize older messages into a single system message
 * - Preserve key decisions, files, errors, and next steps
 *
 * Reference: ADR-0028 - Context Management
 */
class ConversationCompactor(
    private val llmClient: LLMClient,
    private val chatMessageRepository: ChatMessageRepository,
    private val taskRepository: TaskRepository,
    private val configService: ConfigService,
    private val tokenEstimator: TokenEstimator
) {
    // Track compaction statistics
    private var totalCompactions = 0

    /**
     * Check if compaction is needed and perform it.
     *
     * @param taskId Task ID
     * @param currentTokens Current estimated token count
     * @param maxTokens Maximum context window
     * @param threshold Compaction threshold (0.0-1.0)
     * @return True if compaction was performed
     */
    suspend fun maybeCompact(
        taskId: String,
        currentTokens: Int,
        maxTokens: Int,
        threshold: Double = 0.85
    ): Boolean {
        if (currentTokens < maxTokens * threshold) {
            return false
        }

        logger.info {
            "[COMPACT] Threshold reached: $currentTokens / $maxTokens " +
            "(${(currentTokens * 100 / maxTokens)}%)"
        }

        return compact(taskId, maxTokens)
    }

    /**
     * Perform conversation compaction.
     *
     * @param taskId Task ID
     * @param targetTokens Target token count after compaction
     * @return True if compaction was successful
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun compact(taskId: String, _targetTokens: Int): Boolean {
        // Use IO dispatcher for database operations
        return withContext(Dispatchers.IO) {
            val messages = chatMessageRepository.findByTaskId(taskId)
            if (messages.size < 4) {
                logger.debug { "[COMPACT] Too few messages to compact: ${messages.size}" }
                return@withContext false
            }

            // Strategy: Keep last N messages raw, summarize older ones
            val keepRawCount = 4  // Last user message + assistant + 2 tool results
            val toSummarize = messages.dropLast(keepRawCount)
            @Suppress("UNUSED_VARIABLE")
            val _toKeep = messages.takeLast(keepRawCount)

            if (toSummarize.isEmpty()) {
                logger.debug { "[COMPACT] No messages to summarize" }
                return@withContext false
            }

            // Generate summary
            val summary = generateSummary(taskId, toSummarize)

            // Replace old messages with summary
            transaction {
                // Delete old messages
                toSummarize.forEach { msg ->
                    chatMessageRepository.delete(msg.id)
                }

                // Insert summary as system message
                chatMessageRepository.create(
                    taskId = taskId,
                    role = MessageRole.SYSTEM,
                    content = """
                        |<conversation_summary>
                        |$summary
                        |</conversation_summary>
                        |
                        |[Previous ${toSummarize.size} messages were compacted to save context space]
                    """.trimMargin(),
                    metadata = "compaction"
                )
            }

            totalCompactions++

            logger.info {
                "[COMPACT] Compacted ${toSummarize.size} messages into summary " +
                "(${summary.length} chars, total compactions: $totalCompactions)"
            }

            true
        }
    }

    /**
     * Generate summary of messages using weak model.
     *
     * @param taskId Task ID
     * @param messages Messages to summarize
     * @return Generated summary
     */
    private suspend fun generateSummary(
        taskId: String,
        messages: List<pl.jclab.refio.core.db.ChatMessage>
    ): String {
        val conversationText = messages.joinToString("\n\n") { msg ->
            val role = msg.role.name.uppercase()
            val toolInfo = if (!msg.toolCalls.isNullOrEmpty()) {
                "\n[Tools: ${msg.toolCalls.joinToString(", ") { it.name }}]"
            } else ""
            "$role: ${msg.content.take(1000)}$toolInfo"
        }

        val (model, provider) = configService.getModel(ModelOperation.WEAK, taskId)

        val response = llmClient.complete(
            provider = provider,
            model = model,
            messages = listOf(
                LLMMessage(role = "user", content = conversationText)
            ),
            systemPrompt = COMPACTION_SYSTEM_PROMPT,
            taskId = taskId,
            source = "ConversationCompactor",
            maxTokens = 800
        )

        return response.content.trim()
    }

    /**
     * Get total compaction count.
     */
    fun getCompactionCount(): Int = totalCompactions

    companion object {
        /**
         * System prompt for conversation compaction.
         */
        private val COMPACTION_SYSTEM_PROMPT = """
            You are a conversation summarizer for an AI coding assistant.

            Summarize the following conversation, preserving:
            1. Key decisions and conclusions
            2. Files that were read or modified
            3. Important code changes or findings
            4. Current task status and next steps
            5. Any errors or issues encountered

            Format as a concise bullet-point summary (max 500 words).
            Focus on actionable information the agent needs to continue working.

            CONVERSATION:
        """.trimIndent()
    }
}
