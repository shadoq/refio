package pl.jclab.refio.core.llm

import io.mockk.every
import io.mockk.mockk
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.ConfigService
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ModelWindow is the single context-window resolver. These tests pin the behavior the
 * context-overflow guard in TurnExecutor now relies on.
 *
 * Regression: the guard used to call a hardcoded per-model table (`getSafeTokenLimit`) that
 * returned 128k for any model whose name it didn't recognize - which false-flagged "[CTX] overflow"
 * on prompts the provider actually accepted, because the real context budget sized the window
 * correctly. The guard now shares ModelWindow with the budget, so the two can't disagree.
 */
class ModelWindowTest {

    /** Config with no explicit max-context ceiling, so nothing caps the resolved window. */
    private fun configWithoutCeiling() = mockk<ConfigService>().also {
        every { it.get(ConfigKeys.MAX_CONTEXT_SIZE.key, any(), any(), any()) } returns null
    }

    @Test
    fun `the model that false-flagged overflow resolves to its true large window`() {
        // openrouter qwen3.6-plus prefix-matches "qwen/qwen3.6" in ModelDefinitions (1M window).
        // The stale guard returned a flat 128k for it, so a ~165k prompt tripped a false overflow;
        // ModelWindow returns the real 1M, so it doesn't.
        val window = ModelWindow.resolve("openrouter", "qwen/qwen3.6-plus", configWithoutCeiling())

        assertEquals(1_000_000, window, "a known large-window model must not be capped at the old hardcoded 128k")
    }

    @Test
    fun `a model absent from every table falls back to the configured window, not a hardcoded 128k`() {
        val config = configWithoutCeiling()
        every { config.getTyped(ConfigKeys.MAX_CONTEXT_SIZE, any<String>()) } returns 850_000

        val window = ModelWindow.resolve("openrouter", "unregistered-vendor/nope-2099", config, taskId = "t1")

        assertEquals(850_000, window, "an unknown model must use the configured window so the guard matches the budget")
    }

    @Test
    fun `an Ollama window override wins over the global fallback`() {
        val config = configWithoutCeiling()
        every { config.getTyped(ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE, any<String>()) } returns 16_384

        val window = ModelWindow.resolve("ollama", "qwen3.5:9b", config, taskId = "t1")

        assertEquals(16_384, window, "a user's Ollama context override must be honored, not the global default")
    }

    @Test
    fun `a self-hosted OpenAI-compatible server can declare a window far above the built-in default`() {
        // The reason this key exists: /v1/models on servers like llama.cpp reports no
        // context_length, so discovery cannot learn the window and the user must state it.
        val config = configWithoutCeiling()
        every { config.getTyped(ConfigKeys.PROVIDER_CUSTOM_OPENAI_CONTEXT_SIZE, any<String>()) } returns 524_288

        val window = ModelWindow.resolve("generic_openai", "qwen3-coder-local", config, taskId = "t1")

        assertEquals(524_288, window, "a declared window for a custom OpenAI-compatible server must be honored")
    }

    @Test
    fun `an explicitly configured max context is a ceiling, even over a larger provider override`() {
        // "I set 200k, so never send more than 200k" - the real window stops mattering.
        val config = mockk<ConfigService>()
        every { config.get(ConfigKeys.MAX_CONTEXT_SIZE.key, any(), any(), any()) } returns "200000"
        every { config.getTyped(ConfigKeys.PROVIDER_CUSTOM_OPENAI_CONTEXT_SIZE, any<String>()) } returns 524_288

        val window = ModelWindow.resolve("generic_openai", "qwen3-coder-local", config, taskId = "t1")

        assertEquals(200_000, window, "an explicit max context must cap a larger declared provider window")
    }

    @Test
    fun `an unset max context caps nothing, so its default cannot shrink a declared window`() {
        // Regression on the reason the ceiling is conditional: an always-on cap would silently
        // pull a declared 524k window back down to the built-in 128k default.
        val config = configWithoutCeiling()
        every { config.getTyped(ConfigKeys.PROVIDER_CUSTOM_OPENAI_CONTEXT_SIZE, any<String>()) } returns 524_288

        val window = ModelWindow.resolve("generic_openai", "qwen3-coder-local", config, taskId = "t1")

        assertEquals(524_288, window, "a default max context must not act as a ceiling")
    }

    @Test
    fun `the pre-flight estimator and the resolver agree on the window for the same model`() {
        // The bug this pins: TokenEstimator owned a second chain ending at 32 768, so for a
        // locally served model the budget allowed a prompt that pre-flight then rejected.
        val config = configWithoutCeiling()
        every { config.getTyped(ConfigKeys.PROVIDER_CUSTOM_OPENAI_CONTEXT_SIZE, any<String>()) } returns 524_288

        val fromResolver = ModelWindow.resolve("generic_openai", "qwen3-coder-local", config)
        val fromEstimator = TokenEstimator.getMaxContextForModel("qwen3-coder-local", "generic_openai", config)

        assertEquals(fromResolver, fromEstimator, "pre-flight must not use a stricter window than the budget")
        assertEquals(524_288, fromEstimator, "both must see the declared window, not the 32k default")
    }
}
