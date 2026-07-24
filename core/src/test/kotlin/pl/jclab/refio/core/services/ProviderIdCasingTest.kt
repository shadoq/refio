package pl.jclab.refio.core.services

import io.mockk.mockk
import pl.jclab.refio.core.subagents.models.SubagentDefinition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Provider ids must be canonicalized to lowercase at the two "provider/model" parse boundaries,
 * so the same model isn't split into two identities across the system. A debug report showed one
 * session logging both "Openrouter/qwen/qwen3.6-plus" (from a subagent's frontmatter) and
 * "openrouter/qwen/qwen3.6-plus" (from the request path) - which desynchronizes metrics grouping,
 * api-log filters and adapter/pricing lookups that compare the provider by exact string.
 */
class ProviderIdCasingTest {

    @Test
    fun `subagent frontmatter provider is canonicalized to lowercase`() {
        val def = SubagentDefinition(
            name = "security-engineer",
            description = "x",
            systemPrompt = "y",
            model = "Openrouter/qwen/qwen3.6-plus",
        )

        // resolveModel returns (modelId, provider); config is untouched for a "provider/model" value.
        val (modelId, provider) = def.resolveModel(mockk(relaxed = true))

        assertEquals("openrouter", provider, "provider must be lowercase regardless of frontmatter casing")
        assertEquals("qwen/qwen3.6-plus", modelId, "model id keeps its original casing")
    }

    @Test
    fun `ModelSelectionService parseModelString canonicalizes the provider only`() {
        val service = ModelSelectionService(mockk(relaxed = true))

        // Returns (provider, model).
        val (provider, model) = service.parseModelString("OpenRouter/Qwen/Qwen3.6-Plus")

        assertEquals("openrouter", provider, "provider must be lowercase")
        assertEquals("Qwen/Qwen3.6-Plus", model, "model id casing is preserved")
    }
}
