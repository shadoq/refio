package pl.jclab.refio.core.llm

import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.PromptTokenEstimator

/**
 * Utility for estimating token counts and validating context size.
 *
 * Delegates the chars/token math to [PromptTokenEstimator.estimateBase] so all three
 * estimators in :core agree on the base ratio ([PromptTokenEstimator.CHARS_PER_TOKEN_BASE]
 * = 3.5). Was a separate 4 chars/token implementation; the ~14% divergence caused
 * [validateContextSize] to accept prompts that the rest of the stack considered too large.
 */
object TokenEstimator {

    /**
     * Estimate token count for a string.
     *
     * @param text Text to estimate tokens for
     * @return Estimated token count (minimum 1 for non-empty text)
     */
    fun estimateTokens(text: String): Int = PromptTokenEstimator.estimateBase(text)

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
        val sb = StringBuilder()

        // System prompt (backward compatibility)
        systemPrompt?.let { sb.append(it) }

        // Additional system messages — include role overhead ~10 chars each
        systemMessages.forEach { sysMsg ->
            sb.append(sysMsg).append("          ")
        }

        // Messages — include role overhead ~10 chars per message
        messages.forEach { msg ->
            sb.append(msg.content).append("          ")
        }

        return PromptTokenEstimator.estimateBase(sb.toString())
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
    @Suppress("UNUSED_PARAMETER")
    private suspend fun getDefaultContextSize(
        modelId: String,
        provider: String? = null,
        _configService: ConfigService? = null
    ): Int {
        val resolvedProvider = provider ?: inferProvider(modelId)

        val definition = ModelDefinitions.getDefinition(resolvedProvider, modelId)
        if (definition != null) {
            return definition.maxContext
        }

        // Do not trigger provider model discovery from the request hot path.
        // At this stage we only trust static definitions or an already-warmed cache.
        val modelConfig = getModelConfigFromCache(modelId)
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
