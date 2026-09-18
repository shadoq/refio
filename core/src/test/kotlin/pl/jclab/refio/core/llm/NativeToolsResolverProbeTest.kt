package pl.jclab.refio.core.llm

import pl.jclab.refio.core.llm.adapters.OllamaAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A model our tables do not know used to be treated as one that cannot call tools, which silently
 * put the whole agent on its weakest path. These pin the three-way answer: the definition decides
 * when there is one, the provider decides when there is not, and "nobody could say" still means no.
 */
class NativeToolsResolverProbeTest {

    private fun definition(supportsTools: Boolean) = ModelDefinitions
        .syntheticDefinitionFor(provider = "ollama", modelId = "some-model", maxContext = 8_192)
        .copy(supportsFunctionCalling = supportsTools)

    @Test
    fun `an unknown model whose provider confirms tools gets the native channel`() {
        assertTrue(
            shouldUseNativeTools(
                mode = NativeToolsMode.AUTO,
                definition = null,
                modelId = "qwen3.6-35b-ctx64k",
                providerSupportsTools = true,
            )
        )
    }

    @Test
    fun `an unknown model whose provider denies tools stays on the text contract`() {
        assertFalse(
            shouldUseNativeTools(
                mode = NativeToolsMode.AUTO,
                definition = null,
                modelId = "qwen3.6-35b-ctx64k",
                providerSupportsTools = false,
            )
        )
    }

    @Test
    fun `an unknown model whose provider could not be asked stays on the text contract`() {
        assertFalse(
            shouldUseNativeTools(
                mode = NativeToolsMode.AUTO,
                definition = null,
                modelId = "qwen3.6-35b-ctx64k",
                providerSupportsTools = null,
            )
        )
    }

    @Test
    fun `a written definition outranks the provider, in both directions`() {
        // Written precisely to overrule a model that advertises more than it delivers.
        assertFalse(
            shouldUseNativeTools(
                mode = NativeToolsMode.AUTO,
                definition = definition(supportsTools = false),
                modelId = "gemma4:26b",
                providerSupportsTools = true,
            )
        )
        assertTrue(
            shouldUseNativeTools(
                mode = NativeToolsMode.AUTO,
                definition = definition(supportsTools = true),
                modelId = "qwen3.6:35b",
                providerSupportsTools = false,
            )
        )
    }

    @Test
    fun `the session fallback set still wins over everything`() {
        assertFalse(
            shouldUseNativeTools(
                mode = NativeToolsMode.AUTO,
                definition = definition(supportsTools = true),
                modelId = "qwen3.6:35b",
                fallbackFlags = setOf("qwen3.6:35b"),
                providerSupportsTools = true,
            )
        )
    }

    @Test
    fun `the logged reason tells a confirming provider from a silent one`() {
        val confirmed = nativeToolsDecisionReason(
            NativeToolsMode.AUTO, definition = null, modelId = "m", providerSupportsTools = true,
        )
        val denied = nativeToolsDecisionReason(
            NativeToolsMode.AUTO, definition = null, modelId = "m", providerSupportsTools = false,
        )
        val unasked = nativeToolsDecisionReason(
            NativeToolsMode.AUTO, definition = null, modelId = "m", providerSupportsTools = null,
        )

        assertTrue(confirmed.startsWith("NATIVE:"))
        assertTrue(denied.startsWith("JSON:"))
        assertTrue(unasked.startsWith("JSON:"))
        // The two JSON outcomes must not read the same, or the log cannot say what happened.
        assertTrue(denied != unasked)
    }

    @Test
    fun `capabilities are read from the server's own answer`() {
        val derivedModel = mapOf<String, Any?>(
            "capabilities" to listOf("completion", "vision", "Tools", "thinking"),
        )
        val embeddingModel = mapOf<String, Any?>("capabilities" to listOf("embedding"))

        assertEquals(
            setOf("completion", "vision", "tools", "thinking"),
            OllamaAdapter.parseCapabilities(derivedModel),
            "capability names are compared without case",
        )
        assertTrue(OllamaAdapter.CAPABILITY_TOOLS in OllamaAdapter.parseCapabilities(derivedModel)!!)
        assertFalse(OllamaAdapter.CAPABILITY_TOOLS in OllamaAdapter.parseCapabilities(embeddingModel)!!)
    }

    @Test
    fun `a response without a capability list is not an answer of no`() {
        assertNull(
            OllamaAdapter.parseCapabilities(mapOf("model_info" to emptyMap<String, Any?>())),
            "a server too old to report capabilities must not be read as denying them",
        )
    }
}
