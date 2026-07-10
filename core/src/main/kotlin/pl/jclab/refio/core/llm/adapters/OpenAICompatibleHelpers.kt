package pl.jclab.refio.core.llm.adapters

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import pl.jclab.refio.core.llm.BaseLLMAdapter
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.NativeToolCall
import pl.jclab.refio.core.llm.ToolSchemaSanitizer
import pl.jclab.refio.core.tools.base.ToolSchema

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
     * Build the canonical OpenAI `tools` array shape:
     * `[{"type":"function","function":{"name":..,"description":..,"parameters":{...}}}]`.
     *
     * Used by every OpenAI-compatible provider that does NOT have its own bespoke
     * tool-calling protocol (OpenRouter, Z.AI, GenericOpenAI, LM Studio).
     */
    fun buildOpenAIToolsArray(tools: List<ToolSchema>): List<Map<String, Any>> =
        tools.map { rawTool ->
            val tool = ToolSchemaSanitizer.forOpenAI(rawTool).tool
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to tool.parametersJsonSchema,
                )
            )
        }

    /**
     * Parse the `tool_calls` field of an OpenAI chat-completions response message
     * into provider-agnostic [NativeToolCall] entries. Returns an empty list when
     * the field is absent or malformed.
     */
    fun parseOpenAIToolCalls(rawToolCalls: Any?): List<NativeToolCall> {
        @Suppress("UNCHECKED_CAST")
        val toolCalls = rawToolCalls as? List<Map<String, Any?>> ?: return emptyList()
        return toolCalls.mapNotNull { call ->
            val id = call["id"] as? String ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val function = call["function"] as? Map<String, Any?> ?: return@mapNotNull null
            val name = function["name"] as? String ?: return@mapNotNull null
            val argsString = function["arguments"] as? String ?: "{}"
            NativeToolCall(
                id = id,
                name = ToolCallContentNormalizer.normalizeToolName(name),
                argumentsJson = argsString,
            )
        }
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
        checkCancelled: () -> Boolean = { false },
        onRawChunk: ((JsonObject) -> Unit)? = null,
        onToolCallDelta: ((pl.jclab.refio.core.llm.NativeToolCallDelta) -> Unit)? = null,
    ): String? {
        var finishReason: String? = null
        var anyContentEmitted = false
        // Buffer reasoning_content deltas as a last-resort fallback: some reasoning models
        // (e.g. GLM via Z.AI, DeepSeek) stream the whole answer in reasoning_content with an
        // empty content channel. Only surfaced if no content delta ever arrives.
        val reasoningFallback = StringBuilder()
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
                // Parse each SSE line once into a JsonObject with direct field access
                // (cheaper than decoding the whole chunk into nested Maps per line).
                val chunk = JsonParser.parseString(data).asJsonObject
                onRawChunk?.invoke(chunk)
                val first = (chunk.get("choices") as? com.google.gson.JsonArray)
                    ?.firstOrNull() as? JsonObject
                val delta = first?.get("delta") as? JsonObject
                toolCallAccumulator.consumeDelta(delta).forEach { tcDelta -> onToolCallDelta?.invoke(tcDelta) }
                delta.stringField("content")?.takeIf { it.isNotEmpty() }?.let {
                    anyContentEmitted = true
                    onContent(it)
                }
                if (!anyContentEmitted) {
                    delta.stringField("reasoning_content")?.takeIf { it.isNotEmpty() }
                        ?.let { reasoningFallback.append(it) }
                }
                first.stringField("finish_reason")?.let { finishReason = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Rethrow errors thrown by onRawChunk (e.g. mid-stream provider error
                // envelopes from OpenRouter). Malformed chunks raise generic exceptions
                // that match historical behavior — those we silently skip.
                if (e is IllegalStateException) throw e
            }
        }
        if (!anyContentEmitted && reasoningFallback.isNotEmpty()) {
            onContent(reasoningFallback.toString())
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

/** Read a string field from a nullable [JsonObject], tolerating absent and JSON-null values. */
internal fun JsonObject?.stringField(name: String): String? {
    val element = this?.get(name) ?: return null
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
}

/** Read a numeric field from a nullable [JsonObject] as Int, tolerating absent and JSON-null values. */
internal fun JsonObject?.intField(name: String): Int? {
    val element = this?.get(name) ?: return null
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) element.asInt else null
}
