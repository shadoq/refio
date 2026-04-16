package pl.jclab.refio.core.services.turn

import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.ConfigService

class TurnLLMCallerTest {

    private val llmClient = mockk<LLMClient>()
    private val configService = mockk<ConfigService>(relaxed = true)
    private val caller = TurnLLMCaller(
        llmClient = llmClient,
        configService = configService
    )

    @Test
    fun `should disable json response format for local providers`() {
        assertNull(caller.resolveResponseFormat(TaskMode.AGENT, "ollama"))
        assertNull(caller.resolveResponseFormat(TaskMode.PLAN, "lmstudio"))
    }

    @Test
    fun `should keep json response format for remote providers`() {
        assertEquals(
            mapOf("type" to "json_object"),
            caller.resolveResponseFormat(TaskMode.AGENT, "openai")
        )
    }

    @Test
    fun `should pass thinking and no-egress flags to llm client`() {
        every { configService.getModel(any(), any(), any()) } returns ("model-a" to "anthropic")
        every { configService.getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }
        every { configService.getTyped(ConfigKeys.UI_THINKING_ENABLED, "task-1") } returns true
        every { configService.getTyped(ConfigKeys.UI_NO_EGRESS_ENABLED, "task-1") } returns true
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
                contextContent = any(),
                systemMessages = any(),
                kwargs = any()
            )
        } returns LLMResponse(
            content = "{}",
            usage = LLMUsage(1, 1, 2),
            cost = 0.0,
            model = "model-a",
            provider = "anthropic"
        )

        kotlinx.coroutines.runBlocking {
            caller.callLLM(
                taskId = "task-1",
                mode = TaskMode.AGENT,
                prompt = TurnPrompt(
                    systemPrompt = "system",
                    messages = listOf(LLMMessage(role = "user", content = "hello"))
                )
            )
        }

        coVerify {
            llmClient.complete(
                provider = "anthropic",
                model = "model-a",
                messages = any(),
                systemPrompt = "system",
                maxTokens = null,
                temperature = 0.7,
                responseFormat = mapOf("type" to "json_object"),
                thinking = true,
                noEgressEnabled = true,
                stream = false,
                onChunk = null,
                taskId = "task-1",
                subtaskId = null,
                source = "AgentTurnLoop",
                contextContent = null,
                systemMessages = emptyList(),
                kwargs = emptyMap()
            )
        }
    }
}
