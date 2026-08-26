package pl.jclab.refio.core.services.turn

import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.ReasoningEffort
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
        every { configService.getTyped(ConfigKeys.GENERAL_REASONING_EFFORT, "task-1") } returns ReasoningEffort.MEDIUM
        every { configService.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, "task-1") } returns true
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
                reasoningEffort = any(),
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
                // Decision-turn temperature is read from ConfigKeys.AGENT_DECISION_TEMPERATURE;
                // the mock returns each key's default, so this is the 0.7 default. See TurnLLMCaller.
                temperature = 0.7,
                responseFormat = mapOf("type" to "json_object"),
                thinking = true,
                reasoningEffort = "medium",
                noEgressEnabled = true,
                stream = false,
                onChunk = null,
                taskId = "task-1",
                subtaskId = null,
                // Mode is suffixed so PLAN and AGENT turns are distinguishable; this test runs AGENT.
                source = "AgentTurnLoop:AGENT",
                contextContent = null,
                systemMessages = emptyList(),
                kwargs = emptyMap()
            )
        }
    }

    @Test
    fun `turn source is suffixed with the mode so PLAN is distinguishable from AGENT`() {
        every { configService.getModel(any(), any(), any()) } returns ("model-a" to "anthropic")
        every { configService.getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }
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
                reasoningEffort = any(),
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
                mode = TaskMode.PLAN,
                prompt = TurnPrompt(
                    systemPrompt = "system",
                    messages = listOf(LLMMessage(role = "user", content = "hello"))
                )
            )
        }

        coVerify {
            llmClient.complete(
                provider = any(),
                model = any(),
                messages = any(),
                systemPrompt = any(),
                maxTokens = any(),
                temperature = any(),
                responseFormat = any(),
                thinking = any(),
                reasoningEffort = any(),
                noEgressEnabled = any(),
                stream = any(),
                onChunk = any(),
                taskId = any(),
                subtaskId = any(),
                source = "AgentTurnLoop:PLAN",
                contextContent = any(),
                systemMessages = any(),
                kwargs = any()
            )
        }
    }

    @Test
    fun `a stream that aborts before the first token is retried, not fatal`() {
        // e2e regression (qwen3.5:35b crashes on the gate): the decision call bypassed retry, so an
        // Ollama stream that ended before done=true with zero bytes killed the whole turn on the
        // first call. It routes through the retry handler now: no chunk reached the UI, so a clean
        // re-call is safe and the turn recovers instead of returning "no run.json produced".
        every { configService.getModel(any(), any(), any()) } returns ("model-a" to "anthropic")
        every { configService.getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }
        val response = LLMResponse(
            content = "{}", usage = LLMUsage(1, 1, 2), cost = 0.0, model = "model-a", provider = "anthropic"
        )
        var calls = 0
        coEvery {
            llmClient.complete(
                provider = any(), model = any(), messages = any(), systemPrompt = any(),
                maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                reasoningEffort = any(), noEgressEnabled = any(), stream = any(), onChunk = any(),
                taskId = any(), subtaskId = any(), source = any(), contextContent = any(),
                systemMessages = any(), kwargs = any()
            )
        } answers {
            calls++
            if (calls == 1) {
                throw RuntimeException("Ollama stream ended before done=true final chunk (contentBytes=0)")
            }
            response
        }

        val result = kotlinx.coroutines.runBlocking {
            caller.callLLM(
                taskId = "task-1",
                mode = TaskMode.AGENT,
                prompt = TurnPrompt(
                    systemPrompt = "system",
                    messages = listOf(LLMMessage(role = "user", content = "hello"))
                )
            )
        }

        assertEquals("{}", result.content)
        assertEquals(2, calls, "the aborted first call must be retried once and then succeed")
    }

    @Test
    fun `retry budget comes from config, not from the handler defaults`() {
        // The live retry path called callWithRetry with no arguments, so the handler defaults
        // (3 attempts / 1000 ms) always won and the user-facing limits.max_retries knob was
        // silently ignored — including limits.max_retries=1, which must mean "do not retry".
        every { configService.getModel(any(), any(), any()) } returns ("model-a" to "anthropic")
        every { configService.getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }
        every { configService.getTyped(ConfigKeys.MAX_RETRIES, "task-1") } returns 1
        every { configService.getTyped(ConfigKeys.RETRY_DELAY_MS, "task-1") } returns 1L
        var calls = 0
        coEvery {
            llmClient.complete(
                provider = any(), model = any(), messages = any(), systemPrompt = any(),
                maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                reasoningEffort = any(), noEgressEnabled = any(), stream = any(), onChunk = any(),
                taskId = any(), subtaskId = any(), source = any(), contextContent = any(),
                systemMessages = any(), kwargs = any()
            )
        } answers {
            calls++
            throw RuntimeException("rate limit exceeded")
        }

        assertFailsWith<RuntimeException> {
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
        }

        assertEquals(1, calls, "limits.max_retries=1 must cap the turn at a single LLM attempt")
    }
}
