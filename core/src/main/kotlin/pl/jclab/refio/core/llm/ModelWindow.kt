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
 *     `providers.lmstudio.lmstudio_context_size`,
 *     `providers.generic_openai.generic_openai_context_size`)
 *  2. Static [ModelDefinitions.maxContext] for known cloud models
 *  3. The window discovered from the provider's own model listing (in-memory registry cache)
 *  4. [ConfigKeys.MAX_CONTEXT_SIZE] as the last-resort fallback
 *
 * On top of that, [ConfigKeys.MAX_CONTEXT_SIZE] acts as a **ceiling whenever the user set it
 * explicitly**: an explicit value means "never send more than this", regardless of how large
 * the model's real window is. When the key is absent it is only the step-4 fallback, so a
 * default value can never silently shrink an explicitly declared provider window.
 *
 * Callers should prefer [resolve] over reading raw config keys; the centralised path makes
 * it possible to add diagnostics (mismatch warnings, telemetry) in one place later.
 */
object ModelWindow {

    /**
     * Resolve the effective context window in tokens for the given provider/model pair.
     *
     * Reads from [configService], so user overrides win over static defaults. Safe to call
     * on the request hot path — no I/O, no model discovery (step 3 only reads the cache that
     * model listing already populated).
     */
    fun resolve(
        provider: String,
        model: String?,
        configService: ConfigService,
        taskId: String? = null,
    ): Int = applyCeiling(rawWindow(provider, model, configService, taskId), configService, taskId)

    /**
     * Same as [resolve] but for callers that only have a provider — used during config
     * resolution before the model is bound. Returns the provider override if set, else the
     * global [ConfigKeys.MAX_CONTEXT_SIZE] fallback.
     */
    fun resolveProvider(
        provider: String,
        configService: ConfigService,
        taskId: String? = null,
    ): Int = resolve(provider, model = null, configService = configService, taskId = taskId)

    private fun rawWindow(
        provider: String,
        model: String?,
        configService: ConfigService,
        taskId: String?,
    ): Int {
        val providerOverride = providerOverride(provider, configService, taskId)
        if (providerOverride != null) return providerOverride

        if (model != null) {
            val definition = ModelDefinitions.getDefinition(provider, model)
            if (definition != null) return definition.maxContext

            // Models the provider reports but our static tables don't know (new cloud releases,
            // any locally served model). Without this step they fall to the global default,
            // which under-uses large windows.
            val discovered = getModelConfigFromCache(model)
            if (discovered != null) return discovered.maxContext
        }

        return configService.getTyped(ConfigKeys.MAX_CONTEXT_SIZE, taskId)
    }

    /**
     * Cap [window] by an explicitly configured [ConfigKeys.MAX_CONTEXT_SIZE]. A value the user
     * never set is absent from config (the key's default is not persisted), so it stays a
     * fallback and caps nothing.
     */
    private fun applyCeiling(window: Int, configService: ConfigService, taskId: String?): Int {
        val explicitCeiling = configService.get(ConfigKeys.MAX_CONTEXT_SIZE.key, taskId = taskId)
            ?.toIntOrNull()
            ?: return window
        return minOf(window, explicitCeiling)
    }

    private fun providerOverride(provider: String, configService: ConfigService, taskId: String?): Int? {
        return when (provider.lowercase()) {
            "ollama" -> configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE, taskId)
            "lmstudio" -> configService.getTyped(ConfigKeys.PROVIDER_LM_STUDIO_CONTEXT_SIZE, taskId)
            "generic_openai" -> configService.getTyped(ConfigKeys.PROVIDER_CUSTOM_OPENAI_CONTEXT_SIZE, taskId)
            else -> null
        }
    }
}
