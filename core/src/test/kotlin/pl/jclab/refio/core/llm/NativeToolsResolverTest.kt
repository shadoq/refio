package pl.jclab.refio.core.llm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Decision-table tests for [shouldUseNativeTools] and [parseNativeToolsMode].
 *
 * This resolver is the gate that decides whether each LLM request goes through
 * the native function-calling channel or the legacy JSON-in-text channel. The
 * precedence ordering is load-bearing — we want to lock it down with explicit
 * cases so a refactor cannot quietly flip behavior.
 */
class NativeToolsResolverTest {

    private fun model(id: String, supportsTools: Boolean): ModelDefinition =
        ModelDefinition(
            id = id,
            name = id,
            provider = "test",
            capabilities = emptyList(),
            maxContext = 8192,
            costPer1MInput = 0.0,
            costPer1MOutput = 0.0,
            supportsFunctionCalling = supportsTools,
        )

    // ----- parseNativeToolsMode -----

    @Test
    fun `parse mode defaults to AUTO for null or unknown values`() {
        assertEquals(NativeToolsMode.AUTO, parseNativeToolsMode(null))
        assertEquals(NativeToolsMode.AUTO, parseNativeToolsMode(""))
        assertEquals(NativeToolsMode.AUTO, parseNativeToolsMode("auto"))
        assertEquals(NativeToolsMode.AUTO, parseNativeToolsMode("garbage"))
    }

    @Test
    fun `parse mode is case insensitive and trims whitespace`() {
        assertEquals(NativeToolsMode.ALWAYS, parseNativeToolsMode("ALWAYS"))
        assertEquals(NativeToolsMode.ALWAYS, parseNativeToolsMode("  always  "))
        assertEquals(NativeToolsMode.NEVER, parseNativeToolsMode("Never"))
    }

    // ----- shouldUseNativeTools -----

    @Test
    fun `AUTO mode follows model definition supportsFunctionCalling`() {
        val capable = model("gpt-4o-mini", supportsTools = true)
        val notCapable = model("text-only", supportsTools = false)

        assertTrue(shouldUseNativeTools(NativeToolsMode.AUTO, capable, "gpt-4o-mini"))
        assertFalse(shouldUseNativeTools(NativeToolsMode.AUTO, notCapable, "text-only"))
    }

    @Test
    fun `AUTO mode returns false when model definition is missing`() {
        assertFalse(shouldUseNativeTools(NativeToolsMode.AUTO, null, "unknown-model"))
    }

    @Test
    fun `NEVER mode always returns false even for capable models`() {
        val capable = model("gpt-4o-mini", supportsTools = true)
        assertFalse(shouldUseNativeTools(NativeToolsMode.NEVER, capable, "gpt-4o-mini"))
    }

    @Test
    fun `ALWAYS mode returns true even when definition is missing or marked unsupported`() {
        assertTrue(shouldUseNativeTools(NativeToolsMode.ALWAYS, null, "obscure-model"))
        assertTrue(
            shouldUseNativeTools(
                NativeToolsMode.ALWAYS,
                model("unsupported", supportsTools = false),
                "unsupported"
            )
        )
    }

    @Test
    fun `fallbackFlags hit forces JSON path regardless of mode`() {
        val capable = model("gpt-4o-mini", supportsTools = true)
        val fallback = setOf("gpt-4o-mini")

        assertFalse(shouldUseNativeTools(NativeToolsMode.AUTO, capable, "gpt-4o-mini", fallback))
        assertFalse(shouldUseNativeTools(NativeToolsMode.ALWAYS, capable, "gpt-4o-mini", fallback))
        assertFalse(shouldUseNativeTools(NativeToolsMode.NEVER, capable, "gpt-4o-mini", fallback))
    }

    @Test
    fun `fallbackFlags is matched exactly not as prefix`() {
        val capable = model("gpt-4o-mini", supportsTools = true)
        val fallback = setOf("gpt-4o") // not the same id

        assertTrue(shouldUseNativeTools(NativeToolsMode.AUTO, capable, "gpt-4o-mini", fallback))
    }

    @Test
    fun `decision is independent for different model ids in same call site`() {
        val flags = setOf("broken-model")

        assertFalse(shouldUseNativeTools(NativeToolsMode.AUTO, model("broken-model", true), "broken-model", flags))
        assertTrue(shouldUseNativeTools(NativeToolsMode.AUTO, model("healthy-model", true), "healthy-model", flags))
    }
}
