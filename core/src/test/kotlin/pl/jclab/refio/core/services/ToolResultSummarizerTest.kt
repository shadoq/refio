package pl.jclab.refio.core.services

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertTrue
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage

class ToolResultSummarizerTest {

    private val llmClient = mockk<LLMClient>()
    private val configService = mockk<ConfigService>()
    private val taskRepository = mockk<TaskRepository>()

    private val summarizer = ToolResultSummarizer(
        llmClient = llmClient,
        configService = configService,
        taskRepository = taskRepository
    )

    @Test
    fun `should use deterministic compression when llm summary is empty`() = kotlinx.coroutines.test.runTest {
        every { configService.getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }
        every { configService.getTyped(ConfigKeys.TOOL_SUMMARY_ENABLED) } returns true
        every { configService.getTyped(ConfigKeys.TOOL_SUMMARY_MIN_LENGTH) } returns 10
        every { configService.getTyped(ConfigKeys.RECENT_WORK_SUMMARY_MAX_LENGTH) } returns 120
        every { configService.getModel(ModelOperation.WEAK, "task-1") } returns ("qwen3.5:35b" to "ollama")
        every { taskRepository.incrementMetrics(any(), any(), any(), any()) } returns null

        coEvery {
            llmClient.complete(
                provider = any(),
                model = any(),
                messages = any(),
                systemPrompt = any(),
                maxTokens = any(),
                temperature = any(),
                responseFormat = any(),
                thinking = any(),
                noEgressEnabled = any(),
                stream = any(),
                onChunk = any(),
                taskId = any(),
                subtaskId = any(),
                source = any(),
                kwargs = any()
            )
        } returns LLMResponse(
            content = "",
            usage = LLMUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15),
            model = "qwen3.5:35b",
            provider = "ollama",
            cost = 0.0,
            finishReason = "stop"
        )

        // Must exceed GLOBAL_MIN_SKIP_THRESHOLD (1024) so the LLM path is actually
        // taken; otherwise the test would silently stop covering the empty-summary
        // fallback branch.
        val rawOutput = (1..150).joinToString("\n") { "FILE item-$it" }
        val result = summarizer.summarizeToolResult("read_directory", rawOutput, "task-1")

        assertTrue(result.summary.isNotBlank())
        assertTrue(result.summary.length <= rawOutput.length)
    }
}
