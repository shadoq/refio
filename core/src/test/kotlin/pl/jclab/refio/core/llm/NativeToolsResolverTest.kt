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

    // ----- gemma is gated at the registry, not here -----
    // Gemma's Ollama tool template is broken (returns empty content, zero tool_calls).
    // That is handled at the source: ModelDefinitions marks every gemma model
    // supportsFunctionCalling=false (asserted in ModelDefinitionsCharacterizationTest),
    // so AUTO mode already routes them through the JSON path. The resolver itself carries
    // no model-name knowledge — it only reads the definition's capability flag. These
    // cases lock in that contract so a future change can't reintroduce a name blocklist
    // by accident, and they document the deliberate ALWAYS-mode escape hatch.

    @Test
    fun `gemma definition with function calling disabled takes JSON path in AUTO mode`() {
        val gemma = model("gemma4:26b", supportsTools = false)
        assertFalse(shouldUseNativeTools(NativeToolsMode.AUTO, gemma, "gemma4:26b"))
    }

    @Test
    fun `ALWAYS mode is an explicit override and still forces native tools for gemma`() {
        // ALWAYS means the user deliberately demanded native tools; the resolver honors
        // that even for a gemma id. Default behavior (AUTO) is what protects gemma.
        assertTrue(shouldUseNativeTools(NativeToolsMode.ALWAYS, null, "gemma4:26b"))
    }

    // ----- nativeToolsDecisionReason -----
    // The reason string is the only thing that explains a run's native-vs-JSON path from the log.
    // Lock the leading path token (NATIVE/JSON) to the same precedence as shouldUseNativeTools, so a
    // log line can never claim NATIVE while the boolean routed to JSON (the failure that hid the
    // supportsFunctionCalling=false case from debugging).

    private fun assertReasonMatchesVerdict(
        mode: NativeToolsMode,
        definition: ModelDefinition?,
        modelId: String,
        flags: Set<String> = emptySet(),
    ) {
        val verdict = shouldUseNativeTools(mode, definition, modelId, flags)
        val reason = nativeToolsDecisionReason(mode, definition, modelId, flags)
        val token = if (verdict) "NATIVE" else "JSON"
        assertTrue(
            reason.startsWith("$token:"),
            "reason '$reason' must start with '$token:' to match verdict=$verdict",
        )
    }

    @Test
    fun `reason token always agrees with the boolean verdict across the decision table`() {
        val capable = model("cap", supportsTools = true)
        val notCapable = model("nocap", supportsTools = false)
        assertReasonMatchesVerdict(NativeToolsMode.AUTO, capable, "cap")
        assertReasonMatchesVerdict(NativeToolsMode.AUTO, notCapable, "nocap")
        assertReasonMatchesVerdict(NativeToolsMode.AUTO, null, "unknown")
        assertReasonMatchesVerdict(NativeToolsMode.NEVER, capable, "cap")
        assertReasonMatchesVerdict(NativeToolsMode.ALWAYS, null, "obscure")
        assertReasonMatchesVerdict(NativeToolsMode.AUTO, capable, "cap", setOf("cap"))
    }

    @Test
    fun `reason names the supportsFunctionCalling flag when AUTO routes a capable-less model to JSON`() {
        // This is the exact case the user hit: qwen3.5:9b shipped with supportsFunctionCalling=false,
        // so AUTO silently took the JSON path. The reason must spell that out by name.
        val reason = nativeToolsDecisionReason(NativeToolsMode.AUTO, model("qwen", supportsTools = false), "qwen")
        assertTrue(reason.startsWith("JSON:"))
        assertTrue(reason.contains("supportsFunctionCalling=false"), "got: $reason")
    }

    @Test
    fun `reason flags an unknown model distinctly from a known-but-incapable one`() {
        val unknown = nativeToolsDecisionReason(NativeToolsMode.AUTO, null, "mystery")
        assertTrue(unknown.contains("no ModelDefinition"), "got: $unknown")
    }

    @Test
    fun `reason attributes the JSON path to the session fallback set when a prior native call failed`() {
        val reason = nativeToolsDecisionReason(
            NativeToolsMode.AUTO,
            model("cap", supportsTools = true),
            "cap",
            setOf("cap"),
        )
        assertTrue(reason.startsWith("JSON:"))
        assertTrue(reason.contains("fallback set"), "got: $reason")
    }
}
