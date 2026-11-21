package pl.jclab.refio.core.services.turn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.debug.TurnStopReason
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.tools.base.ToolSchema

private val logger = dualLogger("HandoffNoteWriter")

/**
 * The request a turn last sent to the model. A note written after the turn stops repeats it and adds
 * one question at the end, so a server that caches prompt prefixes reads only that question.
 */
internal data class LastLlmRequest(
    val systemPrompt: String?,
    val messages: List<LLMMessage>,
    val kwargs: Map<String, Any?>,
    val model: String,
    val provider: String,
) {
    companion object {
        fun of(prompt: TurnPrompt, nativeToolSchemas: List<ToolSchema>?, model: String, provider: String) = LastLlmRequest(
            systemPrompt = prompt.systemPrompt,
            messages = prompt.messages,
            kwargs = TurnLLMCaller.requestKwargs(nativeToolSchemas, prompt.cacheableSystemLength),
            model = model,
            provider = provider,
        )
    }
}

/** The run a stored row belongs to; all null for the main agent. */
internal data class AgentRef(val instanceId: String?, val name: String?, val depth: Int?)

/** Filled by the turn loop on every model call and read once the loop has ended. */
internal class LastRequestHolder {
    @Volatile var request: LastLlmRequest? = null
    @Volatile var agent: AgentRef? = null
}

/**
 * Asks the model, once a turn has stopped on a limit or a guardrail, what is done and what is left,
 * and stores the answer as a harness row, which the next turn's history shows to the model.
 *
 * Best effort: a note that fails, times out or comes back empty is skipped and the turn ends as it
 * would without it.
 */
internal class HandoffNoteWriter(
    private val llmClient: LLMClient,
    private val chatMessageRepository: ChatMessageRepository,
    private val configService: ConfigService,
    /** Unwraps an answer the model put in the turn's JSON envelope out of habit. */
    private val extractText: (String) -> String = { it },
) {
    /** Returns the stored note, or null when none was written. */
    suspend fun write(taskId: String, reason: TurnStopReason, lastRequest: LastLlmRequest?, agent: AgentRef?): String? {
        if (reason in NO_NOTE || lastRequest == null || GlobalMetrics.isCancelled()) {
            return null
        }
        return try {
            if (!configService.getTyped(ConfigKeys.AGENT_HANDOFF_NOTE, taskId)) {
                return null
            }
            val timeoutMs = configService.getTyped(ConfigKeys.AGENT_HANDOFF_NOTE_TIMEOUT_SECONDS, taskId) * 1000L
            val response = withTimeoutOrNull(timeoutMs) {
                llmClient.complete(
                    provider = lastRequest.provider,
                    model = lastRequest.model,
                    messages = lastRequest.messages + LLMMessage(role = "user", content = request(reason)),
                    systemPrompt = lastRequest.systemPrompt,
                    maxTokens = MAX_TOKENS,
                    temperature = 0.2,
                    thinking = false,
                    noEgressEnabled = configService.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, taskId),
                    stream = false,
                    taskId = taskId,
                    source = SOURCE,
                    // The same tools and cache boundary as the turn's request: a different tool list
                    // would change the prompt's beginning and the server would read it all again.
                    kwargs = lastRequest.kwargs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap(),
                )
            }
            if (response == null) {
                logger.warn { "[HANDOFF_NOTE] taskId=$taskId no note within ${timeoutMs / 1000}s, the turn ends without it" }
                return null
            }
            val note = extractText(response.content).trim()
            if (note.isEmpty()) {
                return null
            }
            val content = "Handoff note left when the previous turn stopped (${why(reason)}):\n$note"
            chatMessageRepository.create(
                taskId = taskId,
                role = MessageRole.SYSTEM,
                content = content,
                metadata = HarnessMessageTypes.metadataFor(HarnessMessageTypes.HANDOFF_NOTE, mapOf("reason" to reason.name)),
                tokensIn = response.usage.inputTokens,
                tokensOut = response.usage.outputTokens,
                cost = response.cost,
                agentInstanceId = agent?.instanceId,
                agentName = agent?.name,
                agentDepth = agent?.depth,
            )
            logger.info { "[HANDOFF_NOTE] taskId=$taskId reason=$reason chars=${note.length}" }
            content
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[HANDOFF_NOTE] taskId=$taskId note failed, the turn ends without it: ${e.message}" }
            null
        }
    }

    private fun request(reason: TurnStopReason) =
        "${HarnessMessageTypes.PREFIX} The turn stops now because ${why(reason)}. Do not call any tool. " +
            "Write a handoff note for whoever continues this task; they will see this conversation only in summary. " +
            "Plain text, under 250 words, with these headings:\n" +
            "Done: what is finished, and how it was checked.\n" +
            "Left: what is still to do.\n" +
            "Next step: the one action to take first.\n" +
            "Files: files created or changed, and their state.\n" +
            "Problems: errors or blockers seen, or \"none\"."

    private fun why(reason: TurnStopReason): String = when (reason) {
        TurnStopReason.MAX_ITERATIONS -> "it used all of its steps"
        TurnStopReason.TIME_LIMIT -> "it ran out of time"
        TurnStopReason.TOOL_ERROR_RATE -> "too many tool calls failed"
        TurnStopReason.REPETITION_LOOP, TurnStopReason.TEXT_REPETITION, TurnStopReason.CONTENT_CHANTING -> "it kept repeating itself"
        TurnStopReason.NOOP_WRITE_STALL -> "its edits stopped changing anything"
        TurnStopReason.VERIFICATION_FAILED -> "the build or tests kept failing"
        TurnStopReason.GUARDIAN_INCOMPLETE -> "the request was judged not delivered"
        else -> reason.name.lowercase().replace('_', ' ')
    }

    companion object {
        const val SOURCE = "HandoffNote"
        private const val MAX_TOKENS = 800

        /**
         * Stops that need no note: the turn finished, the user stopped it or refused a tool (they
         * decide what comes next), the run crashed, or the cost ceiling was reached - the note
         * would be one more paid call over the limit the user set.
         */
        private val NO_NOTE = setOf(
            TurnStopReason.COMPLETED, TurnStopReason.CANCELLED, TurnStopReason.DENIED_TOOL,
            TurnStopReason.COST_LIMIT, TurnStopReason.EXCEPTION,
        )
    }
}
