package pl.jclab.refio.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Context windows for the models added from the Ollama library, taken from their published pages.
 *
 * The number matters beyond documentation: it sizes the context budget, and an over-declared window
 * is what lets a prompt through that the model then truncates in silence. The MLX builds are listed
 * separately because `nemotron-3.5-lightning:30b-mlx` ships 256K where its sibling ships 1M -
 * inheriting the sibling's number would over-declare it fourfold.
 */
class NewOllamaModelDefinitionsTest {

    @Test
    fun `qwen3_8 is a 27B multimodal model with a 256K window`() {
        for (id in listOf("qwen3.8:latest", "qwen3.8:27b", "qwen3.8:27b-mlx")) {
            val def = assertNotNull(ModelDefinitions.getDefinition("ollama", id), "missing: $id")
            assertEquals(256_000, def.maxContext, id)
            assertTrue(def.supportsVision, "$id is multimodal")
            assertTrue(def.supportsFunctionCalling, "$id declares tools")
        }
    }

    @Test
    fun `nemotron 3_5 lightning declares 1M except for the MLX build`() {
        assertEquals(1_000_000, ModelDefinitions.getDefinition("ollama", "nemotron-3.5-lightning:30b")?.maxContext)
        assertEquals(1_000_000, ModelDefinitions.getDefinition("ollama", "nemotron-3.5-lightning:latest")?.maxContext)
        assertEquals(
            256_000,
            ModelDefinitions.getDefinition("ollama", "nemotron-3.5-lightning:30b-mlx")?.maxContext,
            "the MLX build ships a smaller window than its sibling",
        )
        assertEquals(false, ModelDefinitions.getDefinition("ollama", "nemotron-3.5-lightning:30b")?.supportsVision)
    }

    @Test
    fun `muse glimmer is a 30B multimodal model with a 128K window`() {
        for (id in listOf("muse-glimmer:latest", "muse-glimmer:30b", "muse-glimmer:30b-mlx")) {
            val def = assertNotNull(ModelDefinitions.getDefinition("ollama", id), "missing: $id")
            assertEquals(128_000, def.maxContext, id)
            assertTrue(def.supportsVision, "$id is multimodal")
            assertTrue(def.supportsFunctionCalling, "$id declares tools")
        }
    }
}
