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

    @Test
    fun `the model that false-flagged overflow resolves to its true large window`() {
        // openrouter qwen3.6-plus prefix-matches "qwen/qwen3.6" in ModelDefinitions (1M window).
        // The stale guard returned a flat 128k for it, so a ~165k prompt tripped a false overflow;
        // ModelWindow returns the real 1M, so it doesn't. No config is consulted (the model table
        // resolves first), so a relaxed mock suffices.
        val window = ModelWindow.resolve("openrouter", "qwen/qwen3.6-plus", mockk(relaxed = true))

        assertEquals(1_000_000, window, "a known large-window model must not be capped at the old hardcoded 128k")
    }

    @Test
    fun `a model absent from every table falls back to the configured window, not a hardcoded 128k`() {
        val config = mockk<ConfigService>()
        every { config.getTyped(ConfigKeys.MAX_CONTEXT_SIZE, any<String>()) } returns 850_000

        val window = ModelWindow.resolve("openrouter", "unregistered-vendor/nope-2099", config, taskId = "t1")

        assertEquals(850_000, window, "an unknown model must use the configured window so the guard matches the budget")
    }

    @Test
    fun `an Ollama window override wins over the global fallback`() {
        val config = mockk<ConfigService>()
        every { config.getTyped(ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE, any<String>()) } returns 16_384

        val window = ModelWindow.resolve("ollama", "qwen3.5:9b", config, taskId = "t1")

        assertEquals(16_384, window, "a user's Ollama context override must be honored, not the global default")
    }
}
