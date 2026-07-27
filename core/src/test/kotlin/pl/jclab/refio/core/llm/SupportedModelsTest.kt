package pl.jclab.refio.core.llm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportedModelsTest {

    @Test
    fun `deprecated gpt-5_1-codex-mini is not offered on the direct openai provider`() {
        // OpenAI deprecated gpt-5.1-codex-mini on its direct API: /v1/responses returns
        // 404 model_not_found (and an empty completion when streamed). Dropping it from the
        // openai whitelist keeps it out of the offered model list so it can no longer be picked.
        assertFalse(SupportedModels.isSupported("openai", "gpt-5.1-codex-mini"))
    }

    @Test
    fun `gpt-5_1-codex-mini stays available through openrouter which still serves it`() {
        // The same model still works via OpenRouter (chat completions), so removing it from the
        // openai whitelist must not affect the pattern-based OpenRouter whitelist.
        assertTrue(SupportedModels.isSupported("openrouter", "openai/gpt-5.1-codex-mini"))
    }

    @Test
    fun `a live openai model stays supported`() {
        // Guard against over-removal: a current openai model remains whitelisted.
        assertTrue(SupportedModels.isSupported("openai", "gpt-5.4-mini"))
    }
}
