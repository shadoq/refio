package pl.jclab.refio.core.llm.adapters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pl.jclab.refio.core.llm.ModelCapability
import pl.jclab.refio.core.llm.isDecisionOnlyModel
import pl.jclab.refio.core.llm.supportsDecisions

/**
 * A decision-only model (Ollama answers `/api/chat` for it with HTTP 400) must never be offered as
 * a chat model: picked as the WEAK model it silently switched the completion judge off.
 */
class OllamaDecisionCapabilityTest {

    private val chatDefaults = listOf(ModelCapability.CHAT_COMPLETION)

    @Test
    fun `a model reporting only decision keeps only the decision capability`() {
        val caps = OllamaAdapter.withServerDecisionCapability(chatDefaults, setOf("decision")).map { it.name }

        assertEquals(listOf("DECISION"), caps)
        assertTrue(isDecisionOnlyModel(caps))
        assertTrue(supportsDecisions(caps))
    }

    @Test
    fun `a chat model that also answers decisions stays a chat model`() {
        val caps = OllamaAdapter.withServerDecisionCapability(chatDefaults, setOf("completion", "decision", "tools")).map { it.name }

        assertFalse(isDecisionOnlyModel(caps))
        assertTrue(supportsDecisions(caps))
    }

    @Test
    fun `chat models and servers without a capability list are left alone`() {
        assertEquals(chatDefaults, OllamaAdapter.withServerDecisionCapability(chatDefaults, setOf("completion", "tools")))
        assertEquals(chatDefaults, OllamaAdapter.withServerDecisionCapability(chatDefaults, null))
        assertFalse(supportsDecisions(chatDefaults.map { it.name }))
    }
}
