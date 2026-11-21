package pl.jclab.refio.core.llm.decision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Ollama and OpenAI expose the same idea with different JSON: Ollama keys questions and answers by
 * name and calls a yes/no question `noul`; OpenAI uses arrays and calls it `predicate`. A mix-up in
 * either direction makes every judge call fail, and the judge then silently falls back.
 */
class DecisionWireFormatTest {

    private val questions = linkedMapOf(
        "missing" to YesNoQuestion("Is a deliverable missing?"),
        "announces" to YesNoQuestion("Does the reply only announce the next step?")
    )

    @Test
    fun `ollama request keys questions by name as noul with the state as text`() {
        val body = OllamaDecisionClient.buildRequest("nimble:9b", "User request: x", questions, keepAliveSeconds = 1800)

        assertEquals("nimble:9b", body["model"])
        assertEquals("User request: x", body["state"])
        assertEquals("1800s", body["keep_alive"])
        @Suppress("UNCHECKED_CAST")
        val q = body["questions"] as Map<String, Map<String, Any>>
        assertEquals(listOf("missing", "announces"), q.keys.toList())
        assertEquals("noul", q.getValue("missing")["type"])
        assertEquals("Is a deliverable missing?", q.getValue("missing")["instructions"])
    }

    @Test
    fun `ollama response yields the yes probability per question and input tokens`() {
        val json = """
            {"model":"nimble:9b","answers":{
               "missing":{"type":"noul","noul":0.03},
               "announces":{"type":"noul","noul":0.91}},
             "usage":{"input_tokens":1429,"output_tokens":0}}
        """.trimIndent()

        val result = OllamaDecisionClient.parseResponse(json, questions.keys, latencyMs = 978)

        assertEquals(0.03, result.probabilities.getValue("missing"), 1e-9)
        assertEquals(0.91, result.probabilities.getValue("announces"), 1e-9)
        assertEquals(1429, result.inputTokens)
        assertEquals(978, result.latencyMs)
    }

    @Test
    fun `ollama response without one of the asked answers is an error, not a silent zero`() {
        val json = """{"answers":{"missing":{"type":"noul","noul":0.2}},"usage":{"input_tokens":10}}"""

        val e = assertFailsWith<DecisionCallException> {
            OllamaDecisionClient.parseResponse(json, questions.keys, latencyMs = 1)
        }
        assertTrue(e.message!!.contains("announces"))
    }

    @Test
    fun `ollama error body text is surfaced`() {
        assertEquals(
            "\"tev1:4b\" does not support chat",
            OllamaDecisionClient.errorText("""{"error":"\"tev1:4b\" does not support chat"}""")
        )
        assertEquals("plain text", OllamaDecisionClient.errorText("plain text"))
    }

    @Test
    fun `openai request sends questions as a predicate array with the state as input`() {
        val body = OpenAIDecisionClient.buildRequest("gpt-6-luna", "User request: x", questions)

        assertEquals("gpt-6-luna", body["model"])
        assertEquals("User request: x", body["input"])
        @Suppress("UNCHECKED_CAST")
        val q = body["questions"] as List<Map<String, Any>>
        assertEquals(listOf("missing", "announces"), q.map { it["name"] })
        assertTrue(q.all { it["type"] == "predicate" })
        assertEquals("Does the reply only announce the next step?", q[1]["instructions"])
    }

    @Test
    fun `openai response maps answer array to probabilities by name`() {
        val json = """
            {"answers":[
               {"type":"predicate","name":"announces","probability":0.12},
               {"type":"predicate","name":"missing","probability":0.88}],
             "usage":{"input_tokens":512}}
        """.trimIndent()

        val result = OpenAIDecisionClient.parseResponse(json, questions.keys, latencyMs = 170)

        assertEquals(0.88, result.probabilities.getValue("missing"), 1e-9)
        assertEquals(0.12, result.probabilities.getValue("announces"), 1e-9)
        assertEquals(512, result.inputTokens)
    }

    @Test
    fun `openai refusal is an error so the caller can fall back to the LLM judge`() {
        val json = """
            {"answers":[
               {"type":"refusal","name":"missing"},
               {"type":"predicate","name":"announces","probability":0.4}]}
        """.trimIndent()

        assertFailsWith<DecisionCallException> {
            OpenAIDecisionClient.parseResponse(json, questions.keys, latencyMs = 1)
        }
    }
}
