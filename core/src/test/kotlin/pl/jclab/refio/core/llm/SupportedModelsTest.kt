package pl.jclab.refio.core.llm

import kotlin.test.Test
import kotlin.test.assertTrue

class SupportedModelsTest {

    // Verified manually against the direct OpenAI API: every one of these answers
    // 404 model_not_found on /v1/responses (and an empty completion when streamed).
    private val unavailableOnDirectApi = setOf(
        "gpt-5.2-codex-max", "gpt-5.2-codex", "gpt-5.2-codex-mini",
        "gpt-5.1-codex-max", "gpt-5.1-codex", "gpt-5.1-codex-mini",
    )

    // Newer Codex generations still answer on the direct API, so they must stay offered - this
    // is what stops a future cleanup from widening the removal to the whole `*-codex*` family.
    private val liveCodexOnDirectApi = setOf(
        "gpt-5.5-codex-max", "gpt-5.5-codex", "gpt-5.5-codex-mini",
        "gpt-5.4-codex-max", "gpt-5.4-codex", "gpt-5.4-codex-mini",
        "gpt-5.3-codex-max", "gpt-5.3-codex", "gpt-5.3-codex-mini",
    )

    @Test
    fun `codex models OpenAI no longer serves are not offered on the direct openai provider`() {
        val stillOffered = unavailableOnDirectApi.filter { SupportedModels.isSupported("openai", it) }

        assertTrue(
            stillOffered.isEmpty(),
            "these return 404 on the direct OpenAI API and must not be selectable: $stillOffered"
        )
    }

    @Test
    fun `codex generations that still work stay offered on the direct openai provider`() {
        val dropped = liveCodexOnDirectApi.filterNot { SupportedModels.isSupported("openai", it) }

        assertTrue(dropped.isEmpty(), "these still work on the direct OpenAI API: $dropped")
    }

    @Test
    fun `the deprecated codex models stay available through openrouter which still serves them`() {
        // Removing them from the openai whitelist must not touch the pattern-based OpenRouter
        // whitelist - OpenRouter fronts the same models over chat completions and they work there.
        val missing = unavailableOnDirectApi.filterNot {
            SupportedModels.isSupported("openrouter", "openai/$it")
        }

        assertTrue(missing.isEmpty(), "openrouter must keep serving these: $missing")
    }

    @Test
    fun `current coding models are both defined and offered in the model picker`() {
        // The whitelist filters the provider's live model list, so a model defined in
        // ModelDefinitions but missing here never shows up for the user to select.
        val providers = mapOf(
            "openai" to ModelDefinitions.OPENAI_MODELS,
            "anthropic" to ModelDefinitions.ANTHROPIC_MODELS,
            "gemini" to ModelDefinitions.GEMINI_MODELS,
        )
        val newModels = mapOf(
            "openai" to listOf("gpt-6.2-astra", "gpt-6.2-sol", "gpt-6.1-terra"),
            "anthropic" to listOf("claude-fable-5-2", "claude-fable-5-1", "claude-fable-5", "claude-haiku-5-5"),
            "gemini" to listOf(
                "gemini-4-neon", "gemini-4-hydrogen", "gemini-4-carbon", "gemini-4-argon",
                "gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite",
                "gemini-3.5-flash", "gemini-3.1-pro-preview", "gemini-3.1-flash-lite", "gemini-3-flash-preview",
            ),
        )
        val hidden = newModels.flatMap { (provider, ids) ->
            ids.filter { it !in providers.getValue(provider) || !SupportedModels.isSupported(provider, it) }
                .map { "$provider/$it" }
        }

        assertTrue(hidden.isEmpty(), "defined but not selectable: $hidden")
    }

    @Test
    fun `a live non-codex openai model stays supported`() {
        // Guard against over-removal: the non-codex siblings of the removed ids remain whitelisted.
        assertTrue(SupportedModels.isSupported("openai", "gpt-5.2"))
        assertTrue(SupportedModels.isSupported("openai", "gpt-5.1-mini"))
    }
}
