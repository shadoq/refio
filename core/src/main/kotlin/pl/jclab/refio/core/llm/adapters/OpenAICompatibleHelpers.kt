package pl.jclab.refio.core.llm.adapters

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import pl.jclab.refio.core.llm.BaseLLMAdapter
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.utils.GsonInstance.gson

/**
 * Shared helpers for OpenAI-compatible chat adapters
 * (OpenAI, OpenRouter, LMStudio, GenericOpenAI/Z.AI).
 *
 * NOT used by Anthropic, Gemini, Ollama (each has provider-specific protocol).
 */
internal object OpenAICompatibleHelpers {

    /**
     * Map [systemMessages] + [messages] to OpenAI chat-completions message array.
     *
     * Behavior:
     * - non-blank entries from [systemMessages] become `role=system`.
     * - any inline `system` messages from [messages] are dropped (must be passed via [systemMessages]).
     * - `tool` role is remapped to `assistant` because OpenAI-compatible APIs require
     *   `tool_call_id` alongside `role=tool`, which our adapters don't currently emit.
     */
    fun buildMessages(
        adapter: BaseLLMAdapter,
        systemMessages: List<String>,
        messages: List<LLMMessage>
    ): List<Map<String, Any>> = buildList {
        systemMessages.filter { it.isNotBlank() }.forEach { add(mapOf("role" to "system", "content" to it)) }
        messages.filter { it.role != "system" }.forEach { msg ->
            val mappedRole = if (msg.role == "tool") "assistant" else msg.role
            add(mapOf("role" to mappedRole, "content" to adapter.toOpenAiMessageContent(msg)))
        }
    }

    /**
     * Add common OpenAI-compatible knobs from the caller's [kwargs] map to a request body.
     * Only writes keys that are actually present, so callers that pass through fewer
     * kwargs don't end up with unwanted defaults.
     */
    fun MutableMap<String, Any>.addCommonKwargs(kwargs: Map<String, Any>) {
        (kwargs["top_p"] as? Number)?.let { put("top_p", it) }
        (kwargs["frequency_penalty"] as? Number)?.let { put("frequency_penalty", it) }
        (kwargs["presence_penalty"] as? Number)?.let { put("presence_penalty", it) }
        kwargs["stop"]?.let { put("stop", it) }
    }

    /**
     * Resolve the effective `max_tokens` value: caller-provided cap, configured limit,
     * and per-model definition cap, all combined. Logs a warning when the requested
     * value exceeds the model's hard limit and is being clamped down.
     *
     * @param requested caller's [maxTokens] (null/0 means "use config limit").
     * @param configLimit configured maximum from `ConfigKeys.MAX_OUTPUT_SIZE`.
     * @param modelLimit per-model cap from [pl.jclab.refio.core.llm.ModelDefinitions], or null/0 if unknown.
     */
    /**
     * Consume an OpenAI chat.completions SSE stream from [channel]:
     * - skips blank lines, lines not starting with `data: `, and the `[DONE]` terminator.
     * - accumulates `tool_calls` deltas via [toolCallAccumulator].
     * - invokes [onContent] with each non-empty `content` delta.
     * - returns the last seen `finish_reason` (or `"cancelled"` if [checkCancelled] returns true mid-stream).
     *
     * Malformed chunks are silently skipped to match historical adapter behavior.
     * CancellationException (guardrail trip) propagates out.
     */
    suspend fun consumeChatCompletionsSSE(
        channel: ByteReadChannel,
        toolCallAccumulator: ToolCallContentNormalizer.OpenAiStreamingToolCallAccumulator,
        onContent: (String) -> Unit,
        checkCancelled: () -> Boolean = { false }
    ): String? {
        var finishReason: String? = null
        while (!channel.isClosedForRead) {
            if (checkCancelled()) {
                finishReason = "cancelled"
                break
            }
            val line = channel.readUTF8Line(limit = Int.MAX_VALUE) ?: continue
            if (line.isBlank() || !line.startsWith("data: ")) continue
            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]") break
            try {
                @Suppress("UNCHECKED_CAST")
                val chunk = gson.fromJson(data, Map::class.java) as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val choices = chunk["choices"] as? List<Map<String, Any?>> ?: emptyList()
                val first = choices.firstOrNull() ?: emptyMap()
                @Suppress("UNCHECKED_CAST")
                val delta = first["delta"] as? Map<String, Any?>
                toolCallAccumulator.consumeDelta(delta)
                (delta?.get("content") as? String)?.takeIf { it.isNotEmpty() }?.let(onContent)
                (first["finish_reason"] as? String)?.let { finishReason = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Match historical behavior: silently skip malformed chunks.
            }
        }
        return finishReason
    }

    fun resolveEffectiveMaxTokens(
        requested: Int?,
        configLimit: Int,
        modelLimit: Int?,
        providerTag: String,
        model: String,
        log: (() -> String) -> Unit
    ): Int {
        val capped = if (requested != null && requested > 0) minOf(requested, configLimit) else configLimit
        return if (modelLimit != null && modelLimit > 0 && capped > modelLimit) {
            log { "[$providerTag] Requested max_tokens=$capped exceeds model limit ($modelLimit) for $model - clamping to safe value" }
            modelLimit
        } else {
            capped
        }
    }
}
