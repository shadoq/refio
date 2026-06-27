package pl.jclab.refio.core.tools.implementations

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.tools.PathSandbox
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmCallToolTest {

    private val llmClient = mockk<LLMClient>()
    private val configService = mockk<ConfigService>()
    private val sandbox = mockk<PathSandbox>(relaxed = true)

    private val tool = LlmCallTool(
        llmClient = llmClient,
        configService = configService,
        sandbox = sandbox,
    )

    private fun stubModel() {
        coEvery { configService.getModel(ModelOperation.WEAK, any(), any()) } returns ("weak-model" to "ollama")
    }

    private fun stubComplete(content: String, outputTokens: Int) {
        coEvery {
            llmClient.complete(
                provider = any(),
                model = any(),
                messages = any(),
                systemPrompt = any(),
                maxTokens = any(),
                temperature = any(),
                taskId = any(),
                source = any(),
                thinking = any(),
                stream = any(),
            )
        } returns LLMResponse(
            content = content,
            usage = LLMUsage(inputTokens = 10, outputTokens = outputTokens, totalTokens = 10 + outputTokens),
            model = "weak-model",
            provider = "ollama",
            cost = 0.0,
            finishReason = "stop",
        )
    }

    @Test
    fun `empty LLM response is reported as an error, not silent success`() = runTest {
        // A reasoning model that returns only thinking (stripped) leaves no answer. Returning
        // success with empty output silently corrupts the caller's data flow; it must fail loud.
        stubModel()
        stubComplete(content = "", outputTokens = 200)

        val result = tool.execute(mapOf("prompt" to "classify this", "data" to "some text"))

        assertFalse(result.success, "empty model output must not be a successful tool result")
        assertTrue(
            result.error.orEmpty().contains("empty response", ignoreCase = true),
            "error should explain the empty response, was: ${result.error}",
        )
    }

    @Test
    fun `non-empty LLM response is returned as success`() = runTest {
        stubModel()
        stubComplete(content = "transport", outputTokens = 1)

        val result = tool.execute(mapOf("prompt" to "classify this", "data" to "some text"))

        assertTrue(result.success)
        assertTrue(result.output.orEmpty().contains("transport"))
    }
}
