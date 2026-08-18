package pl.jclab.refio.core.llm.adapters

import org.junit.jupiter.api.Test
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMToolCall
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An assistant turn that called a tool has to reach the model as a call, not as prose or nothing.
 *
 * Measured on gemma4:26b, which answers with a bare tool call and keeps its reasoning in a separate
 * thinking channel: with the call missing from the history the model saw a `read_file` result it
 * had no record of requesting and issued the identical call four times in a row until the loop
 * guard ended the turn. Ollama's API takes the arguments as an object, unlike the OpenAI shape.
 */
class OllamaAdapterToolCallHistoryTest {

    private val adapter = OllamaAdapter(model = "gemma4:26b")

    @Test
    fun `an assistant turn with no text still carries its tool call`() {
        val rendered = adapter.toOllamaMessages(
            systemMessages = listOf("You are an agent."),
            messages = listOf(
                LLMMessage(role = "user", content = "Analyze the compose file."),
                LLMMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(
                        LLMToolCall(id = "c1", name = "read_file", argumentsJson = """{"path":"docker-compose.yml"}""")
                    ),
                ),
                LLMMessage(role = "tool", content = "services:\n  api:"),
            ),
        )

        val assistant = rendered.single { it["role"] == "assistant" }
        @Suppress("UNCHECKED_CAST")
        val calls = assistant["tool_calls"] as List<Map<String, Any>>
        @Suppress("UNCHECKED_CAST")
        val function = calls.single()["function"] as Map<String, Any>

        assertEquals("read_file", function["name"])
        assertEquals(mapOf("path" to "docker-compose.yml"), function["arguments"], "Ollama wants an object, not a JSON string")
        assertFalse(assistant["content"].toString().contains("read_file"), "the call must not be duplicated into prose")
    }

    @Test
    fun `a turn with both text and a call keeps both`() {
        val rendered = adapter.toOllamaMessages(
            systemMessages = emptyList(),
            messages = listOf(
                LLMMessage(
                    role = "assistant",
                    content = "Checking the compose file.",
                    toolCalls = listOf(LLMToolCall(id = "c1", name = "read_file", argumentsJson = """{"path":"a.yml"}""")),
                ),
            ),
        )

        val assistant = rendered.single()
        assertEquals("Checking the compose file.", assistant["content"])
        assertTrue(assistant.containsKey("tool_calls"))
    }

    // A model can emit arguments that are not a JSON object at all; losing the call entirely would
    // put us back in the loop this guards against, so the raw string goes through instead.
    @Test
    fun `unparsable arguments are sent verbatim rather than dropping the call`() {
        val rendered = adapter.toOllamaMessages(
            systemMessages = emptyList(),
            messages = listOf(
                LLMMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(LLMToolCall(id = "c1", name = "grep_search", argumentsJson = "not json at all")),
                ),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val function = ((rendered.single()["tool_calls"] as List<Map<String, Any>>).single()["function"]) as Map<String, Any>
        assertEquals("not json at all", function["arguments"])
    }

    @Test
    fun `a turn with neither text nor calls carries no tool_calls key`() {
        val rendered = adapter.toOllamaMessages(
            systemMessages = emptyList(),
            messages = listOf(LLMMessage(role = "user", content = "hello")),
        )

        assertFalse(rendered.single().containsKey("tool_calls"))
    }
}
