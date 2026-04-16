package pl.jclab.refio.core.llm

import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("SupportedModels")

/**
 * Whitelist of supported LLM models across all providers.
 *
 * This is the single source of truth for which models are tested and supported by the plugin.
 * Models are fetched dynamically from provider APIs, then filtered through this list.
 *
 * Benefits:
 * - Centralized model support management
 * - Users only see models they have access to AND that are supported
 * - Easy to add new models without modifying adapter code
 * - No need for complex blacklist filters in each adapter
 */
object SupportedModels {

    /**
     * OpenAI supported models.
     *
     * Supported families:
     * - GPT-4o (latest, most capable)
     * - GPT-4 Turbo (high performance)
     * - GPT-4 (original)
     * - GPT-3.5 Turbo (cost-effective)
     * - O1 series (reasoning models)
     */
    private val OPENAI_SUPPORTED = setOf<String>(
        // GPT-5.4
        "gpt-5.4",
        "gpt-5.4-mini",
        "gpt-5.4-nano",
        "gpt-5.4-codex-max",
        "gpt-5.4-codex",
        "gpt-5.4-codex-mini",
        // GPT-5.3
        "gpt-5.3",
        "gpt-5.3-mini",
        "gpt-5.3-nano",
        "gpt-5.3-codex-max",
        "gpt-5.3-codex",
        "gpt-5.3-codex-mini",
        // GPT-5.2
        "gpt-5.2",
        "gpt-5.2-mini",
        "gpt-5.2-nano",
        "gpt-5.2-codex-max",
        "gpt-5.2-codex",
        "gpt-5.2-codex-mini",
        // GPT-5.1
        "gpt-5.1-codex-max",
        "gpt-5.1-codex",
        "gpt-5.1-codex-mini",
        "gpt-5.1",
        "gpt-5.1-mini",
        "gpt-5.1-nano",
        // GPT-5.1
        "gpt-5",
        "gpt-5-mini",
        "gpt-5-nano",
        // GPT-4.1
        "gpt-4.1",
        "gpt-4.1-mini",
        "gpt-4.1-nano",
        // GPT-4o
        "gpt-4o",
        "gpt-4o-mini",
        // O1 reasoning models
        "o1",
        "o1-mini",
        "o3",
        "o3-mini",
        "o3-pro",
        "o4-mini",
    )

    /**
     * Anthropic supported models.
     */
    private val ANTHROPIC_SUPPORTED = setOf<String>(
        // Opus models
        "claude-opus-4-7",
        "anthropic.claude-opus-4-7",
        "claude-opus-4-6",
        "anthropic.claude-opus-4-6-v1",
        "claude-opus-4-5",
        "claude-opus-4-5-20251101",
        "claude-opus-4-1",
        "claude-opus-4-1-20250805",
        "claude-opus-4-0",
        "claude-opus-4-20250514",
        // Sonnet models
        "claude-sonnet-4-5-20250929",
        "claude-sonnet-4-5",
        "claude-sonnet-4-20250514",
        "claude-sonnet-4-0",
        "claude-3-7-sonnet-20250219",
        "claude-3-7-sonnet-latest",
        // Haiku models
        "claude-haiku-4-5-20251001",
        "claude-haiku-4-5",
        "claude-haiku-4-5@20251001",
        "anthropic.claude-haiku-4-5-20251001-v1:0",
        "claude-3-5-haiku-20241022",
        "claude-3-5-haiku-latest",
        "claude-3-haiku-20240307",
    )

    /**
     * OpenRouter supported models.
     *
     * OpenRouter provides access to many providers through a unified API.
     * We support popular models from major providers.
     *
     * Format: "provider/model" (e.g., "google/gemini-2.0-flash-exp")
     */
    private val OPENROUTER_PATTERNS = setOf<Regex>(
        // Google Gemini models (1.x and 2.x series)
        Regex("^.*gpt-.*"),
        Regex("^.*amazon.*"),
        Regex("^.*mistralai.*"),
        Regex("^.*arcee.*"),
        Regex("^.*tngtech.*"),
        Regex("^.*deepcogito.*"),
        Regex("^.*kwaipilot.*"),
        Regex("^.*perplexity.*"),
        Regex("^.*baidu.*"),
        Regex("^.*anthropic.*"),
        Regex("^.*alibaba.*"),
        Regex("^.*nvidia.*"),
        Regex("^.*qwen.*"),
        Regex("^.*claude.*"),
        Regex("^.*minimax.*"),
        Regex("^.*deepseek.*"),
        Regex("^.*gemini.*"),
        Regex("^.*x-ai.*"),
        Regex("^.*z-ai.*")
    )

    /**
     * Gemini supported models.
     */
    private val GEMINI_SUPPORTED = setOf(
        "gemini-3-pro-preview",
        "gemini-3-flash",
        "gemini-3-flash-lite",
        "gemini-2.5-pro",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite",
        "gemini-pro-latest",
        "gemini-flash-latest",
        "gemini-flash-lite-latest",
    )

    /**
     * LM Studio supported models (local) - allow all IDs returned by the server.
     */
    private val LM_STUDIO_SUPPORTED = setOf(
        Regex("^.*")
    )

    private val CUSTOM_OPENAI_SUPPORTED = setOf(
        Regex("^.*")
    )

    private val ZAI_SUPPORTED = setOf(
        Regex("^.*glm.*"),
        Regex("^.*z-ai.*"),
        Regex("^.*")
    )

    /**
     * Ollama supported models.
     */
    private val OLLAMA_SUPPORTED = setOf<Regex>(
        Regex("^.*"),
    )

    /**
     * Check if a model is supported for a given provider.
     *
     * @param provider Provider name (openai, anthropic, openrouter, ollama)
     * @param modelId Model identifier
     * @return true if model is supported, false otherwise
     */
    fun isSupported(provider: String, modelId: String): Boolean {
        return when (provider.lowercase()) {
            "openai" -> {
                val supported = modelId in OPENAI_SUPPORTED
                if (!supported) {
                    logger.debug { "[WHITELIST] OpenAI model not supported: $modelId" }
                }
                supported
            }

            "anthropic" -> {
                val supported = modelId in ANTHROPIC_SUPPORTED
                if (!supported) {
                    logger.debug { "[WHITELIST] Anthropic model not supported: $modelId" }
                }
                supported
            }

            "openrouter" -> {
                val supported = OPENROUTER_PATTERNS.any { it.matches(modelId) }
                if (!supported) {
                    logger.debug { "[WHITELIST] OpenRouter model not supported: $modelId" }
                }
                supported
            }

            "gemini" -> {
                val supported = modelId in GEMINI_SUPPORTED
                if (!supported) {
                    logger.debug { "[WHITELIST] Gemini model not supported: $modelId" }
                }
                supported
            }

            "ollama" -> {
                val supported = OLLAMA_SUPPORTED.any { it.matches(modelId) }
                if (!supported) {
                    logger.debug { "[WHITELIST] Ollama model not supported: $modelId" }
                }
                supported
            }

            "lmstudio" -> {
                val supported = LM_STUDIO_SUPPORTED.any { it.matches(modelId) }
                if (!supported) {
                    logger.debug { "[WHITELIST] LM Studio model not supported: $modelId" }
                }
                supported
            }

            "generic_openai" -> {
                val supported = CUSTOM_OPENAI_SUPPORTED.any { it.matches(modelId) }
                if (!supported) {
                    logger.debug { "[WHITELIST] Custom OpenAI model not supported: $modelId" }
                }
                supported
            }

            "zai" -> {
                val supported = ZAI_SUPPORTED.any { it.matches(modelId) }
                if (!supported) {
                    logger.debug { "[WHITELIST] Z.AI model not supported: $modelId" }
                }
                supported
            }

            else -> {
                logger.warn { "[WHITELIST] Unknown provider: $provider" }
                false
            }
        }
    }

}
