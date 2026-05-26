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
    private val tokenEstimator: PromptTokenEstimator
) {
    // Track compaction statistics
    private var totalCompactions = 0

    /**
     * Check if compaction is needed and perform it.
     *
     * **Contract for `currentTokens`:** must be the token count of the FINAL rendered prompt
     * (after TurnPromptBuilder + ContextService compression), NOT a raw sum of stored
     * `ChatMessage.content` lengths. The sole production caller (AgentTurnLoop.kt) passes
     * `tokenEstimator.checkFits(tempPrompt, ...).second` which satisfies this. Don't change
     * the caller to pass raw stored content — see ConversationSummaryService for why that
     * would cause premature compaction (large unsummarized tool results inflate the count
     * ~20× over what actually reaches the LLM).
     *
     * @param taskId Task ID
     * @param currentTokens Token count of the rendered prompt (see contract above)
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
    suspend fun compact(taskId: String, @Suppress("UNUSED_PARAMETER") targetTokens: Int): Boolean {
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

            // Validate summary is non-empty — don't delete messages if LLM returned garbage
            if (summary.isBlank()) {
                logger.warn { "[COMPACT] LLM returned empty summary, aborting compaction to preserve messages" }
                return@withContext false
            }

            // Merge with previous compacted summary to prevent summary-of-summary degradation
            val mergedSummary = mergeWithPreviousSummary(toSummarize, summary)

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
                    content = "$mergedSummary\n\n[Previous ${toSummarize.size} messages were compacted to save context space]",
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
        // Extract previous compacted summaries from messages (cascade handling)
        val previousSummaries = messages
            .filter { it.role == MessageRole.SYSTEM }
            .mapNotNull { extractCompactedSummary(it.content) }

        // Format non-summary messages for LLM — skip pure compaction summary messages
        val conversationText = messages
            .filter { msg ->
                msg.role != MessageRole.SYSTEM ||
                    extractCompactedSummary(msg.content) == null
            }
            .joinToString("\n\n") { msg ->
                val role = msg.role.name.uppercase()
                val toolInfo = if (!msg.toolCalls.isNullOrEmpty()) {
                    "\n[Tools: ${msg.toolCalls.joinToString(", ") { it.name }}]"
                } else ""
                "$role: ${msg.content.take(1000)}$toolInfo"
            }

        // If previous summaries exist, include them as structured context for merging
        val previousContext = if (previousSummaries.isNotEmpty()) {
            "\n\nPREVIOUS SESSION CONTEXT (merge into your summary, do not re-summarize):\n" +
                previousSummaries.joinToString("\n---\n")
        } else ""

        val userMessage = conversationText + previousContext

        val (model, provider) = configService.getModel(ModelOperation.WEAK, taskId)

        val response = llmClient.complete(
            provider = provider,
            model = model,
            messages = listOf(
                LLMMessage(role = "user", content = userMessage)
            ),
            systemPrompt = COMPACTION_SYSTEM_PROMPT,
            taskId = taskId,
            source = "ConversationCompactor",
            maxTokens = 1200
        )

        return response.content.trim()
    }

    /**
     * Get total compaction count.
     */
    fun getCompactionCount(): Int = totalCompactions

    /**
     * If old messages contain a previous compacted_summary, merge its sections
     * with the new summary to prevent summary-of-summary degradation.
     */
    private fun mergeWithPreviousSummary(
        oldMessages: List<pl.jclab.refio.core.db.ChatMessage>,
        newSummary: String
    ): String {
        val previousSummary = oldMessages
            .filter { it.role == MessageRole.SYSTEM }
            .mapNotNull { extractCompactedSummary(it.content) }
            .lastOrNull()
            ?: return newSummary

        if (!newSummary.contains("<compacted_summary>")) return newSummary

        val previousSections = parseSummarySections(previousSummary)
        val newSections = parseSummarySections(newSummary)
        val merged = mergeSections(previousSections, newSections)
        return renderMergedSummary(merged)
    }

    private fun extractCompactedSummary(content: String): String? {
        val regex = Regex("<compacted_summary>([\\s\\S]*?)</compacted_summary>")
        return regex.find(content)?.groupValues?.get(1)?.trim()
    }

    private data class SummarySections(
        val decisions: List<String> = emptyList(),
        val filesModified: List<String> = emptyList(),
        val findings: List<String> = emptyList(),
        val currentState: List<String> = emptyList(),
        val nextSteps: List<String> = emptyList()
    )

    private fun parseSummarySections(summary: String): SummarySections {
        fun extractSection(tag: String): List<String> {
            val regex = Regex("<$tag>([\\s\\S]*?)</$tag>")
            val content = regex.find(summary)?.groupValues?.get(1) ?: return emptyList()
            return content.lines()
                .map { it.trim() }
                .filter { it.startsWith("- ") }
                .map { it.removePrefix("- ").trim() }
                .filter { it.isNotBlank() && it != "None" }
        }
        return SummarySections(
            decisions = extractSection("decisions"),
            filesModified = extractSection("files_modified"),
            findings = extractSection("findings"),
            currentState = extractSection("current_state"),
            nextSteps = extractSection("next_steps")
        )
    }

    private fun mergeSections(
        previous: SummarySections,
        current: SummarySections
    ): SummarySections {
        fun merge(prev: List<String>, curr: List<String>, maxItems: Int = 7): List<String> {
            val currentLower = curr.map { it.lowercase() }.toSet()
            val unique = prev.filter { it.lowercase() !in currentLower }
            return (curr + unique).take(maxItems)
        }
        return SummarySections(
            decisions = merge(previous.decisions, current.decisions),
            filesModified = merge(previous.filesModified, current.filesModified),
            findings = merge(previous.findings, current.findings),
            currentState = current.currentState,
            nextSteps = current.nextSteps
        )
    }

    private fun renderMergedSummary(sections: SummarySections): String {
        fun renderSection(items: List<String>): String =
            if (items.isEmpty()) "- None" else items.joinToString("\n") { "- $it" }

        return """
<compacted_summary>
<decisions>
${renderSection(sections.decisions)}
</decisions>

<files_modified>
${renderSection(sections.filesModified)}
</files_modified>

<findings>
${renderSection(sections.findings)}
</findings>

<current_state>
${renderSection(sections.currentState)}
</current_state>

<next_steps>
${renderSection(sections.nextSteps)}
</next_steps>
</compacted_summary>""".trimIndent()
    }

    companion object {
        /**
         * System prompt for conversation compaction.
         * Uses structured output format to prevent summary-of-summary degradation.
         */
        private val COMPACTION_SYSTEM_PROMPT = """
            You are a conversation compactor for an AI coding assistant.

            Summarize the following conversation into EXACTLY this structured format.
            Each section is REQUIRED — if nothing fits a section, write "None".

            <compacted_summary>
            <decisions>
            - [Each key decision made by the user or agent, one per line]
            - [Include: architectural choices, tool preferences, approach selections]
            </decisions>

            <files_modified>
            - [Each file path that was created, edited, or deleted]
            - [Format: path — what was changed]
            </files_modified>

            <findings>
            - [Key discoveries: bugs found, patterns identified, errors encountered]
            - [Include: test results, build outputs, analysis conclusions]
            </findings>

            <current_state>
            - [What is the current status of the task]
            - [What was the last action taken]
            - [What is partially complete or in progress]
            </current_state>

            <next_steps>
            - [What still needs to be done]
            - [Any blockers or open questions]
            </next_steps>
            </compacted_summary>

            RULES:
            - Use bullet points (- ) for each item
            - Be specific: include file paths, function names, error messages
            - Max 3-5 items per section
            - Total max 600 words
            - If the conversation already contains a <compacted_summary>, merge its content with new information — do NOT nest summaries

            CONVERSATION:
        """.trimIndent()
    }
}
