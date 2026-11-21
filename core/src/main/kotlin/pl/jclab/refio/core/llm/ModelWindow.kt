package pl.jclab.refio.core.llm

import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.ConfigService

/** Which step of [ModelWindow]'s resolution order produced the window a UI shows. */
enum class ContextWindowSource {
    /** The user set the provider's own context size key (Ollama, LM Studio, custom OpenAI-compatible). */
    PROVIDER_EXPLICIT,

    /** The provider's context size key was never set, so its built-in default answered (e.g. Ollama 32768). */
    PROVIDER_DEFAULT,

    /** The static model table knows this model. */
    MODEL_TABLE,

    /** The provider's own model listing reported the window. */
    DISCOVERED,

    /** Nothing knew the model, so the global max context size answered. */
    FALLBACK,

    /** An explicitly set global max context size lowered the window that one of the steps above found. */
    CEILING,
}

/** A context window in tokens together with where that number came from. */
data class ResolvedContextWindow(val tokens: Int, val source: ContextWindowSource)

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
    ): Int = applyCeiling(rawWindow(provider, model, configService, taskId).tokens, configService, taskId)

    /**
     * Same number as [resolve], plus the step that produced it, so a UI can say why the window is
     * what it is (a never-configured Ollama size reads very differently from a declared one).
     *
     * When an explicit [ConfigKeys.MAX_CONTEXT_SIZE] lowers the window, the source is
     * [ContextWindowSource.CEILING]: the shown number is then the ceiling, not what the provider
     * or the model tables said.
     */
    fun resolveWithSource(
        provider: String,
        model: String?,
        configService: ConfigService,
        taskId: String? = null,
    ): ResolvedContextWindow {
        val raw = rawWindow(provider, model, configService, taskId)
        val tokens = applyCeiling(raw.tokens, configService, taskId)
        val source = when {
            tokens < raw.tokens -> ContextWindowSource.CEILING
            raw.source != null -> raw.source
            providerContextKey(provider)?.let { isExplicitlySet(it, configService, taskId) } == true ->
                ContextWindowSource.PROVIDER_EXPLICIT
            else -> ContextWindowSource.PROVIDER_DEFAULT
        }
        return ResolvedContextWindow(tokens, source)
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
    ): Int = resolve(provider, model = null, configService = configService, taskId = taskId)

    /**
     * The window before the ceiling. [source] is null when the provider's own size key answered:
     * telling an explicit value from the key's default costs one more config read, which only
     * [resolveWithSource] pays, so [resolve] reads config exactly as before.
     */
    private class RawWindow(val tokens: Int, val source: ContextWindowSource?)

    private fun rawWindow(
        provider: String,
        model: String?,
        configService: ConfigService,
        taskId: String?,
    ): RawWindow {
        val providerKey = providerContextKey(provider)
        if (providerKey != null) return RawWindow(configService.getTyped(providerKey, taskId), source = null)

        if (model != null) {
            val definition = ModelDefinitions.getDefinition(provider, model)
            if (definition != null) return RawWindow(definition.maxContext, ContextWindowSource.MODEL_TABLE)

            // Models the provider reports but our static tables don't know (new cloud releases,
            // any locally served model). Without this step they fall to the global default,
            // which under-uses large windows.
            val discovered = getModelConfigFromCache(model)
            if (discovered != null) return RawWindow(discovered.maxContext, ContextWindowSource.DISCOVERED)
        }

        return RawWindow(configService.getTyped(ConfigKeys.MAX_CONTEXT_SIZE, taskId), ContextWindowSource.FALLBACK)
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

    private fun providerContextKey(provider: String): ConfigKey<Int>? {
        return when (provider.lowercase()) {
            "ollama" -> ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE
            "lmstudio" -> ConfigKeys.PROVIDER_LM_STUDIO_CONTEXT_SIZE
            "generic_openai" -> ConfigKeys.PROVIDER_CUSTOM_OPENAI_CONTEXT_SIZE
            else -> null
        }
    }

    /**
     * True when [key] holds a value someone set (stored setting, config file or run override).
     * The same test the ceiling uses: a key's default is never persisted, so an unset key reads
     * back as null from the raw getter while the typed getter returns the default.
     */
    private fun isExplicitlySet(key: ConfigKey<Int>, configService: ConfigService, taskId: String?): Boolean =
        configService.get(key.key, taskId = taskId)?.let(key.parser) != null
}
