package pl.jclab.refio.core.llm

import org.junit.jupiter.api.Test
import pl.jclab.refio.core.services.ConfigService.Companion.DEFAULT_CONTEXT_SIZE
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterization tests for ModelDefinitions.
 *
 * Sprint 0 baseline: asercje na OBECNE zachowanie. Sprint 1 #2 zmieni
 * `syntheticDefinitionFor` na `syntheticDefinitionFor` + WARN log — semantycznie bez zmian,
 * więc te testy po rename powinny dalej przechodzić (z zmienioną nazwą wywołania).
 */
class ModelDefinitionsCharacterizationTest {

    @Test
    fun `syntheticDefinitionFor returns synthetic ModelDefinition with provided context length`() {
        val result = ModelDefinitions.syntheticDefinitionFor(
            provider = "openai",
            modelId = "gpt-9999-future-model",
            maxContext = 131072
        )

        assertEquals("gpt-9999-future-model", result.id)
        assertEquals("gpt-9999-future-model", result.name)
        assertEquals("openai", result.provider)
        assertEquals(131072, result.maxContext)
        assertEquals("Unknown model (synthetic definition)", result.description)
    }

    @Test
    fun `syntheticDefinitionFor defaults maxContext when not provided`() {
        val result = ModelDefinitions.syntheticDefinitionFor(
            provider = "ollama",
            modelId = "some-local-model"
        )

        assertEquals(DEFAULT_CONTEXT_SIZE, result.maxContext)
    }

    @Test
    fun `syntheticDefinitionFor sets conservative capability defaults`() {
        val result = ModelDefinitions.syntheticDefinitionFor(
            provider = "openai",
            modelId = "unknown"
        )

        // Current defaults: chat only, no vision/reasoning/functions, streaming enabled.
        assertEquals(listOf(ModelCapability.CHAT_COMPLETION), result.capabilities)
        assertEquals(ModelType.TEXT, result.modelType)
        assertFalse(result.supportsVision)
        assertFalse(result.supportsReasoning)
        assertTrue(result.supportsStreaming)
        assertFalse(result.supportsFunctionCalling)
        assertTrue(result.active)
    }

    @Test
    fun `syntheticDefinitionFor uses zero pricing`() {
        val result = ModelDefinitions.syntheticDefinitionFor(
            provider = "openai",
            modelId = "unknown"
        )

        assertEquals(0.0, result.costPer1MInput)
        assertEquals(0.0, result.costPer1MOutput)
        assertNull(result.maxOutputTokens)
    }

    @Test
    fun `getDefinition returns null for unknown model`() {
        // Characterizes the pre-condition for syntheticDefinitionFor: real code path
        // does `getDefinition(...) ?: syntheticDefinitionFor(...)`. If this ever returns
        // non-null for an unknown model, the fallback chain is silently broken.
        val result = ModelDefinitions.getDefinition("openai", "gpt-9999-definitely-fake")
        assertNull(result)
    }

    @Test
    fun `getDefinition returns real definition for known model`() {
        val result = ModelDefinitions.getDefinition("openai", "gpt-4o-mini")
        assertNotNull(result)
        assertEquals("openai", result.provider)
    }
}
