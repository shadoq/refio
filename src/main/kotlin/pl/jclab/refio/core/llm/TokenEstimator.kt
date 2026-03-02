package pl.jclab.refio.core.llm

import pl.jclab.refio.core.services.ConfigService

/**
 * Utility for estimating token counts and validating context size.
 *
 * Uses a simple heuristic: ~4 characters = 1 token (industry standard approximation).
 * This is conservative for English text; actual tokenization varies by model.
 */
object TokenEstimator {

    private const val CHARS_PER_TOKEN = 4

    /**
     * Estimate token count for a string.
     *
     * @param text Text to estimate tokens for
     * @return Estimated token count (minimum 1 for non-empty text)
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length / CHARS_PER_TOKEN).coerceAtLeast(1)
    }

    /**
     * Estimate total tokens for LLM request.
     *
     * @param messages Conversation messages
     * @param systemPrompt Optional system prompt (for backward compatibility)
     * @param systemMessages List of system messages (policies, context, etc.)
     * @return Estimated total input tokens
     */
    fun estimateRequestTokens(
        messages: List<LLMMessage>,
        systemPrompt: String? = null,
        systemMessages: List<String> = emptyList()
    ): Int {
        var totalChars = 0

        // System prompt (backward compatibility)
        systemPrompt?.let { totalChars += it.length }

        // Additional system messages
        systemMessages.forEach { sysMsg ->
            totalChars += sysMsg.length + 10  // Include overhead for each system message
        }

        // Messages (include role overhead ~10 chars per message)
        messages.forEach { msg ->
            totalChars += msg.content.length + 10
        }

        return (totalChars / CHARS_PER_TOKEN).coerceAtLeast(1)
    }

    /**
     * Validate that request doesn't exceed model context limit.
     *
     * @param messages Conversation messages
     * @param systemPrompt Optional system prompt
     * @param maxContextTokens Model's maximum context window
     * @param reserveForOutput Reserve tokens for output (default 4096)
     * @throws ContextTooLargeException if estimated tokens exceed limit
     */
    fun validateContextSize(
        messages: List<LLMMessage>,
        systemPrompt: String? = null,
        maxContextTokens: Int,
        reserveForOutput: Int = 4096
    ) {
        val estimatedTokens = estimateRequestTokens(messages, systemPrompt)
        val availableTokens = maxContextTokens - reserveForOutput

        if (estimatedTokens > availableTokens) {
            throw ContextTooLargeException(
                estimatedTokens = estimatedTokens,
                maxContextTokens = maxContextTokens,
                availableTokens = availableTokens
            )
        }
    }

    /**
     * Get model's max context size, with fallback.
     *
     * Priority: ModelDefinitions (single source of truth) -> ModelRegistry -> pattern-based fallback
     *
     * @param modelId Model identifier
     * @param provider Provider name (openai, anthropic, ollama, openrouter). If null, will be inferred.
     * @param configService Optional config service for dynamic lookup
     * @return Max context tokens (defaults to 128000 if unknown)
     */
    suspend fun getMaxContextForModel(
        modelId: String,
        provider: String? = null,
        configService: ConfigService? = null
    ): Int {
        return getDefaultContextSize(modelId, provider, configService)
    }

    /**
     * Get default context size based on model definitions.
     * Used as last resort when model is not in ModelDefinitions or ModelRegistry.
     */
    private suspend fun getDefaultContextSize(
        modelId: String,
        provider: String? = null,
        configService: ConfigService? = null
    ): Int {
        val resolvedProvider = provider ?: inferProvider(modelId)

        val definition = ModelDefinitions.getDefinition(resolvedProvider, modelId)
        if (definition != null) {
            return definition.maxContext
        }

        val modelConfig = getModelConfig(modelId, configService)
        if (modelConfig != null) {
            return modelConfig.maxContext
        }

        return ConfigService.DEFAULT_CONTEXT_SIZE
    }
}

/**
 * Exception thrown when context exceeds model's limit.
 */
class ContextTooLargeException(
    val estimatedTokens: Int,
    val maxContextTokens: Int,
    val availableTokens: Int
) : Exception(
    "Context too large: estimated $estimatedTokens tokens, but model allows only $availableTokens tokens " +
    "(max context: $maxContextTokens tokens, minus output reserve). " +
    "Please reduce context size by removing conversation history, files, or context providers."
)
