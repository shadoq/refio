package pl.jclab.refio.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import pl.jclab.refio.core.config.ConfigKeys

/**
 * Base classes for LLM adapters in Refio.
 *
 * Defines the common interface that all LLM providers must implement.
 */

/**
 * Single message in conversation
 */
sealed interface LLMContentPart {
    data class Text(val text: String) : LLMContentPart
    data class Image(
        val mediaType: String,
        val base64Data: String,
        val detail: String? = null
    ) : LLMContentPart
}

data class LLMMessage(
    val role: String,  // "user", "assistant", "system"
    val content: String,
    val parts: List<LLMContentPart> = if (content.isNotBlank()) {
        listOf(LLMContentPart.Text(content))
    } else {
        emptyList()
    }
) {
    fun textOnlyContent(): String {
        if (parts.isEmpty()) return content

        val text = parts.mapNotNull { part ->
            when (part) {
                is LLMContentPart.Text -> part.text
                is LLMContentPart.Image -> null
            }
        }.joinToString("")

        return if (text.isNotBlank()) text else content
    }

    fun hasImageParts(): Boolean = parts.any { it is LLMContentPart.Image }
}

/**
 * Token usage statistics
 */
data class LLMUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int
)

/**
 * Single chunk from streaming LLM response (US-027)
 */
data class StreamChunk(
    val delta: String,                    // Incremental content
    val thinking: String? = null,         // Incremental thinking content (reasoning models)
    val finishReason: String? = null,     // "stop", "length", "cancelled", etc.
    val usage: LLMUsage? = null           // Present only on final chunk
)

/**
 * Response from LLM provider
 */
data class LLMResponse(
    val content: String,
    val usage: LLMUsage,
    val model: String,
    val provider: String,
    val cost: Double,  // Estimated cost in USD
    val finishReason: String? = null,
    val rawResponse: Map<String, Any?>? = null,  // Allow nullable values in response map
    val thinking: String? = null           // Complete thinking process (reasoning models)
)

/**
 * Abstract base class for LLM adapters.
 *
 * All LLM providers (Ollama, OpenAI, Anthropic) must implement this interface.
 *
 * API logging: Adapters log API calls using PluginLogger for monitoring.
 */
abstract class BaseLLMAdapter(
    val model: String,
    val provider: String
) {
    internal fun toOpenAiMessageContent(message: LLMMessage): Any {
        val normalizedParts = normalizeMessageParts(message)
        if (normalizedParts.size == 1 && normalizedParts.first() is LLMContentPart.Text) {
            return (normalizedParts.first() as LLMContentPart.Text).text
        }

        return normalizedParts.map { part ->
            when (part) {
                is LLMContentPart.Text -> mapOf(
                    "type" to "text",
                    "text" to part.text
                )

                is LLMContentPart.Image -> mapOf(
                    "type" to "image_url",
                    "image_url" to mapOf(
                        "url" to "data:${part.mediaType};base64,${part.base64Data}",
                        "detail" to (part.detail ?: "auto")
                    )
                )
            }
        }
    }

    protected fun toAnthropicMessageContent(message: LLMMessage): Any {
        val normalizedParts = normalizeMessageParts(message)
        if (normalizedParts.size == 1 && normalizedParts.first() is LLMContentPart.Text) {
            return (normalizedParts.first() as LLMContentPart.Text).text
        }

        return normalizedParts.map { part ->
            when (part) {
                is LLMContentPart.Text -> mapOf(
                    "type" to "text",
                    "text" to part.text
                )

                is LLMContentPart.Image -> mapOf(
                    "type" to "image",
                    "source" to mapOf(
                        "type" to "base64",
                        "media_type" to part.mediaType,
                        "data" to part.base64Data
                    )
                )
            }
        }
    }

    protected fun toGeminiParts(message: LLMMessage): List<Map<String, Any>> {
        return normalizeMessageParts(message).map { part ->
            when (part) {
                is LLMContentPart.Text -> mapOf("text" to part.text)
                is LLMContentPart.Image -> mapOf(
                    "inlineData" to mapOf(
                        "mimeType" to part.mediaType,
                        "data" to part.base64Data
                    )
                )
            }
        }
    }

    protected fun normalizeMessageParts(message: LLMMessage): List<LLMContentPart> {
        if (message.parts.isNotEmpty()) {
            return message.parts
        }
        return if (message.content.isNotBlank()) {
            listOf(LLMContentPart.Text(message.content))
        } else {
            emptyList()
        }
    }

    /**
     * Send chat request to LLM provider (unified method for streaming and non-streaming).
     *
     * This method handles both streaming and non-streaming modes:
     * - When streaming=false: Returns complete LLMResponse immediately
     * - When streaming=true: Calls onStreamChunk for each incremental update, then returns complete LLMResponse
     *
     * @param messages List of conversation messages
     * @param systemMessages List of system messages (policies, context, etc.) sent before conversation history
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature (0.0 - 1.0)
     * @param streaming Enable streaming mode (default: false)
     * @param onStreamChunk Callback for streaming updates (called for each chunk when streaming=true)
     * @param kwargs Additional provider-specific parameters
     * @return LLMResponse with complete generated content and metadata
     * @throws Exception On API error or connection failure
     */
    abstract suspend fun chat(
        messages: List<LLMMessage>,
        systemMessages: List<String> = emptyList(),
        maxTokens: Int? = null,
        temperature: Double = 0.7,
        streaming: Boolean = false,
        onStreamChunk: ((StreamChunk) -> Unit)? = null,
        kwargs: Map<String, Any> = emptyMap()
    ): LLMResponse

    /**
     * Estimate cost in USD based on token usage.
     *
     * Default implementation returns 0.0 (for local models).
     * Cloud providers should override this.
     *
     * @param usage Token usage statistics
     * @return Estimated cost in USD
     */
    open fun estimateCost(usage: LLMUsage): Double {
        return 0.0
    }

    /**
     * Cleanup resources (optional)
     */
    open suspend fun close() {
        // Override in subclasses if cleanup needed
    }

    /**
     * Redact API keys, tokens, and passwords from text before logging.
     *
     * Patterns:
     * - OpenAI/Anthropic keys: sk-[32+ chars]
     * - Bearer tokens: Bearer [token]
     * - Passwords in JSON: "password": "value"
     */
    protected fun redactSecrets(text: String): String {
        return pl.jclab.refio.core.security.SecureLogger.redact(text)
    }

    /**
     * Universal parameter normalization based on model definition.
     *
     * This method applies model-specific transformations to request parameters:
     * 1. Applies default parameters from model definition (if not already set)
     * 2. Maps parameter names (param_mappings: e.g., "max_tokens" → "max_completion_tokens")
     * 3. Removes unsupported parameters (remove_params: e.g., "temperature" for reasoning models)
     * 4. Maps message roles (message_role_mappings: e.g., "system" → "user" for reasoning models)
     * 5. Removes null values (some APIs don't tolerate null)
     *
     * Similar to Python's `normalize_request_params` in base.py:284-418
     *
     * @param params Input parameters map
     * @param definition Model definition from ModelDefinitions registry (null = no transformations)
     * @return Transformed parameters map with model-specific adjustments applied
     */
    protected fun normalizeRequestParams(
        params: Map<String, Any?>,
        definition: ModelDefinition?
    ): Map<String, Any> {
        if (definition == null) {
            // No definition = no transformations, just remove nulls
            return params.filterValues { it != null }.mapValues { it.value!! }
        }

        val mutableParams = params.toMutableMap()

        // Step 1: Apply default parameters (only if not already present)
        for ((key, value) in definition.defaultParams) {
            if (!mutableParams.containsKey(key) || mutableParams[key] == null) {
                mutableParams[key] = value
            }
        }

        // Step 1.5: Apply reasoningTokensMultiplier for reasoning models
        // For reasoning models (e.g., gpt-5.1-codex-*), multiply max_tokens by the configured multiplier
        // to accommodate both reasoning tokens and output tokens
        if (definition.supportsReasoning && definition.reasoningTokensMultiplier != null) {
            // Find max_tokens parameter (could be under different names before mapping)
            val maxTokensKey = mutableParams.keys.find { it in listOf("max_tokens", "max_output_tokens", "max_completion_tokens") }
            if (maxTokensKey != null) {
                val baseMaxTokens = (mutableParams[maxTokensKey] as? Number)?.toInt()
                    ?: definition.maxOutputTokens
                    ?: ConfigKeys.MAX_OUTPUT_SIZE.default
                val adjustedMaxTokens = (baseMaxTokens * definition.reasoningTokensMultiplier).toInt()
                mutableParams[maxTokensKey] = adjustedMaxTokens
            }
        }

        // Step 2: Map parameter names (param_mappings)
        // Example: "max_tokens" → "max_completion_tokens" for GPT-5 models
        val mappedParams = mutableMapOf<String, Any?>()
        for ((key, value) in mutableParams) {
            val mappedKey = definition.paramMappings[key] ?: key
            mappedParams[mappedKey] = value
        }

        // Step 3: Remove unsupported parameters (remove_params)
        // Example: Remove "temperature", "top_p" for reasoning models
        val filteredParams = mappedParams.filterKeys { key ->
            key !in definition.removeParams
        }.toMutableMap()

        // Step 4: Map message roles (if messages field exists)
        // Example: "system" → "user" for reasoning models that don't support system role
        // This is handled by checking if model definition has message_role_mappings capability
        // Currently implemented in adapters directly (see OpenAIAdapter).
        // Consolidate here if more adapters need role mapping in the future.

        // Step 5: Remove null values (some APIs don't tolerate null)
        val cleanedParams = filteredParams.filterValues { it != null }.mapValues { it.value!! }

        return cleanedParams
    }
}
