package pl.jclab.refio.core.llm

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.ConfigService

/**
 * Single resolver for "context window size in tokens" — the only function in :core that
 * is allowed to answer "how big a prompt can this model accept?".
 *
 * Before this existed, the same question was answered four different ways:
 *  - [ContextBudgetResolver.resolveContextSize] (Ollama-aware, used by context budget math)
 *  - [TokenEstimator.getMaxContextForModel] (ModelDefinitions-only, used by LLMClient validation)
 *  - a hardcoded per-model table in PromptTokenEstimator (since removed) that fell back to 128k for
 *    any model not listed, which false-flagged context overflow on newer OpenRouter models
 *  - direct [ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE] reads (OllamaAdapter `num_ctx`)
 *
 * These often disagreed (e.g. user override = 16 384 but compaction saw 32 768), which is
 * the root cause of silent Ollama truncation when the rendered prompt exceeded `num_ctx`.
 *
 * Resolution order:
 *  1. User-configured provider-specific override (`providers.ollama.ollama_context_size`,
 *     `providers.lmstudio.lmstudio_context_size`)
 *  2. Static [ModelDefinitions.maxContext] for known cloud models
 *  3. [ConfigKeys.MAX_CONTEXT_SIZE] global fallback
 *
 * Callers should prefer [resolve] over reading raw config keys; the centralised path makes
 * it possible to add diagnostics (mismatch warnings, telemetry) in one place later.
 */
object ModelWindow {

    /**
     * Resolve the effective context window in tokens for the given provider/model pair.
     *
     * Reads from [configService], so user overrides win over static defaults. Safe to call
     * on the request hot path — no I/O, no model discovery.
     */
    fun resolve(
        provider: String,
        model: String?,
        configService: ConfigService,
        taskId: String? = null,
    ): Int {
        val providerOverride = providerOverride(provider, configService, taskId)
        if (providerOverride != null) return providerOverride

        if (model != null) {
            val definition = ModelDefinitions.getDefinition(provider, model)
            if (definition != null) return definition.maxContext
        }

        return configService.getTyped(ConfigKeys.MAX_CONTEXT_SIZE, taskId)
    }

    /**
     * Same as [resolve] but for callers that only have a provider — used during config
     * resolution before the model is bound. Returns the provider override if set, else the
     * global [ConfigKeys.MAX_CONTEXT_SIZE] fallback.
     */
    fun resolveProvider(
        provider: String,
        configService: ConfigService,
        taskId: String? = null,
    ): Int {
        return providerOverride(provider, configService, taskId)
            ?: configService.getTyped(ConfigKeys.MAX_CONTEXT_SIZE, taskId)
    }

    private fun providerOverride(provider: String, configService: ConfigService, taskId: String?): Int? {
        return when (provider.lowercase()) {
            "ollama" -> configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE, taskId)
            "lmstudio" -> configService.getTyped(ConfigKeys.PROVIDER_LM_STUDIO_CONTEXT_SIZE, taskId)
            else -> null
        }
    }
}
