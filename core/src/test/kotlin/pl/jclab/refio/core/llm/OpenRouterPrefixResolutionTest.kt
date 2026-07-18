package pl.jclab.refio.core.llm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * OpenRouter definitions are resolved by first-matching prefix over an ordered map,
 * so a broad family entry placed above a narrower one silently steals its models and
 * hands back the wrong context window or pricing. These tests pin the specific models
 * whose correct resolution depends on that ordering.
 */
class OpenRouterPrefixResolutionTest {

    private fun definitionFor(modelId: String): ModelDefinition =
        assertNotNull(
            ModelDefinitions.getDefinition("openrouter", modelId),
            "no OpenRouter definition resolved for $modelId"
        )

    @Test
    fun `gpt-5_1 keeps its 400k window instead of falling back to the 128k gpt- family`() {
        assertEquals(400_000, definitionFor("openai/gpt-5.1").maxContext)
        assertEquals(400_000, definitionFor("openai/gpt-5.1-codex").maxContext)
        assertEquals(400_000, definitionFor("openai/gpt-5.1-codex-mini").maxContext)
    }

    @Test
    fun `gpt-5_1-chat is the 128k exception within the 400k gpt-5_1 family`() {
        assertEquals(128_000, definitionFor("openai/gpt-5.1-chat").maxContext)
    }

    @Test
    fun `premium tiers resolve to their own pricing rather than the base family rate`() {
        val gpt5Pro = definitionFor("openai/gpt-5-pro")
        assertEquals(15.0, gpt5Pro.costPer1MInput)
        assertEquals(120.0, gpt5Pro.costPer1MOutput)

        val novaPremier = definitionFor("amazon/nova-premier-v1")
        assertEquals(1_000_000, novaPremier.maxContext)
        assertEquals(2.50, novaPremier.costPer1MInput)
    }

    @Test
    fun `image tiers keep their small window instead of inheriting the text family window`() {
        assertEquals(32_768, definitionFor("google/gemini-2.5-flash-image").maxContext)
    }

    @Test
    fun `qwen3 VL variants are multimodal unlike the text-only qwen3 family`() {
        assertTrue(definitionFor("qwen/qwen3-vl-32b-instruct").supportsVision)
        assertTrue(definitionFor("qwen/qwen3-vl-8b-thinking").supportsVision)
    }

    @Test
    fun `minimax m2 keeps its 204k window without regressing the m2_5 sibling prefix`() {
        assertEquals(204_800, definitionFor("minimax/minimax-m2").maxContext)
        assertEquals(196_608, definitionFor("minimax/minimax-m2.5").maxContext)
    }

    @Test
    fun `deepseek v3_2 keeps its 163k window instead of the 128k base family`() {
        assertEquals(163_840, definitionFor("deepseek/deepseek-v3.2-exp").maxContext)
        assertEquals(128_000, definitionFor("deepseek/deepseek-r1").maxContext)
    }

    @Test
    fun `newly added vendors resolve to a real definition rather than null`() {
        assertEquals(128_000, definitionFor("deepcogito/cogito-v2.1-671b").maxContext)
        assertEquals(200_000, definitionFor("perplexity/sonar-pro-search").maxContext)
        assertEquals(131_000, definitionFor("ibm-granite/granite-4.0-micro").maxContext)
        assertEquals(131_072, definitionFor("thedrummer/cydonia-24b-v4.1").maxContext)
        assertEquals(256_000, definitionFor("relace/relace-apply-3").maxContext)
    }

    @Test
    fun `models_dev slug models resolve to their own window instead of the base family`() {
        assertEquals(1_050_000, definitionFor("openai/gpt-5.6-luna").maxContext)
        assertEquals(1_048_576, definitionFor("moonshotai/kimi-k3").maxContext)
        assertEquals(500_000, definitionFor("xai/grok-4.5").maxContext)
        assertEquals(1_000_000, definitionFor("zhipuai/glm-5.2").maxContext)
        assertEquals(1_048_576, definitionFor("google/gemini-omni-flash-preview").maxContext)
    }

    @Test
    fun `newly added labs resolve to a real definition rather than null`() {
        assertEquals(256_000, definitionFor("thinkingmachines/inkling").maxContext)
        assertEquals(1_000_000, definitionFor("meta/muse-spark-1.1").maxContext)
        assertEquals(262_144, definitionFor("poolside/laguna-xs-2.1").maxContext)
        assertEquals(1_000_000, definitionFor("meituan/longcat-2.0").maxContext)
        assertEquals(262_144, definitionFor("deepreinforce/ornith-1.0-397b").maxContext)
    }

    @Test
    fun `gemini omni flash falls back to JSON envelope because it emits no native tool_calls`() {
        assertFalse(definitionFor("google/gemini-omni-flash-preview").supportsFunctionCalling)
    }

    @Test
    fun `whitelist admits the models_dev lab slugs`() {
        assertTrue(SupportedModels.isSupported("openrouter", "thinkingmachines/inkling"))
        assertTrue(SupportedModels.isSupported("openrouter", "xai/grok-4.5"))
        assertTrue(SupportedModels.isSupported("openrouter", "meituan/longcat-2.0"))
        assertTrue(SupportedModels.isSupported("openrouter", "deepreinforce/ornith-1.0-9b"))
        assertTrue(SupportedModels.isSupported("openrouter", "zhipuai/glm-5.2"))
    }

    @Test
    fun `o-series research models pass the OpenRouter whitelist that only matched gpt- ids`() {
        assertTrue(SupportedModels.isSupported("openrouter", "openai/o3-deep-research"))
        assertTrue(SupportedModels.isSupported("openrouter", "openai/o4-mini-deep-research"))
    }

    @Test
    fun `whitelist admits the newly added vendors`() {
        assertTrue(SupportedModels.isSupported("openrouter", "ibm-granite/granite-4.0-micro"))
        assertTrue(SupportedModels.isSupported("openrouter", "thedrummer/cydonia-24b-v4.1"))
        assertTrue(SupportedModels.isSupported("openrouter", "relace/relace-apply-3"))
    }
}
