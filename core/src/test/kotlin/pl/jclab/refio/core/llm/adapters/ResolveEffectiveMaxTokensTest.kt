package pl.jclab.refio.core.llm.adapters

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Locks in the max-tokens resolution contract: an explicit caller request (advance_code_editing
 * asking for the model's full output budget so a large file generates in ONE shot) must be honored
 * up to the model's hard limit, NOT throttled by the global `limits.max_output_size` default. The
 * config value is only the default for unspecified calls and the safety ceiling for unknown models.
 *
 * The old semantics (`min(request, configLimit)`) capped every editor call at ~32k tokens even for
 * Claude sonnet (64k real limit), so large single-file HTML deterministically truncated mid-file.
 */
class ResolveEffectiveMaxTokensTest {

    private fun resolve(requested: Int?, configLimit: Int, modelLimit: Int?): Int =
        OpenAICompatibleHelpers.resolveEffectiveMaxTokens(
            requested = requested,
            configLimit = configLimit,
            modelLimit = modelLimit,
            providerTag = "TEST",
            model = "test-model",
            log = {},
        )

    @Test
    fun `explicit request above config but below model limit is honored, not clamped to config`() {
        // The bug: a 64k-capable model was capped at the 32k config default, truncating large files.
        assertEquals(48_000, resolve(requested = 48_000, configLimit = 32_000, modelLimit = 64_000))
    }

    @Test
    fun `explicit request above the model limit is clamped to the model limit`() {
        // advance_code_editing asks for a huge budget on purpose; the model's hard cap is the ceiling.
        assertEquals(64_000, resolve(requested = 1_000_000, configLimit = 32_000, modelLimit = 64_000))
    }

    @Test
    fun `no explicit request falls back to the config default`() {
        assertEquals(32_000, resolve(requested = null, configLimit = 32_000, modelLimit = 64_000))
        assertEquals(32_000, resolve(requested = 0, configLimit = 32_000, modelLimit = 64_000))
    }

    @Test
    fun `unknown model limit falls back to the config value as a safety ceiling`() {
        // For a model with no definition we cannot know its real cap, so config bounds the request
        // to avoid sending an over-limit value the provider would reject.
        assertEquals(32_000, resolve(requested = 1_000_000, configLimit = 32_000, modelLimit = null))
        assertEquals(32_000, resolve(requested = 1_000_000, configLimit = 32_000, modelLimit = 0))
    }
}
