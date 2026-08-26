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
     * Get the model's max context window in tokens.
     *
     * Thin wrapper over [ModelWindow.resolve] that only adds provider inference. This used to
     * own a second resolution chain whose last resort was [ConfigService.DEFAULT_CONTEXT_SIZE],
     * so the pre-flight check in [LLMClient] and the context budget disagreed: for a locally
     * served model the budget sized the prompt against the configured window while pre-flight
     * rejected it at 32 768, and no setting could raise that. There is now exactly one resolver.
     *
     * @param modelId Model identifier
     * @param provider Provider name (openai, anthropic, ollama, openrouter). If null, inferred from [modelId].
     * @param configService Config source; user overrides and the explicit ceiling are read from it.
     * @param taskId Task scope for config lookups, when the caller has one.
     */
    fun getMaxContextForModel(
        modelId: String,
        provider: String? = null,
        configService: ConfigService,
        taskId: String? = null,
    ): Int {
        val resolvedProvider = provider ?: inferProvider(modelId)
        return ModelWindow.resolve(resolvedProvider, modelId, configService, taskId)
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
