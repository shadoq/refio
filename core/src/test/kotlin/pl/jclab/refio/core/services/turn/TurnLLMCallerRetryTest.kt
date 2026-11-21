package pl.jclab.refio.core.services.turn

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.errors.LLMErrorMapper
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.LLMRetryHandler
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import java.net.ConnectException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A long task runs for hours against a model server that restarts, reloads a model or answers 503
 * for a while. One such break must not end the round: the agent's model call waits and tries again
 * (after 2, 4, 8, 16 and then every 30 seconds) for up to `llm.retry_max_seconds` of waiting, and
 * gives up at once on a request the server will never accept.
 */
class TurnLLMCallerRetryTest {

    private val llmClient = mockk<LLMClient>(relaxed = true)
    private val configService = mockk<ConfigService>(relaxed = true)
    private val callTimes = mutableListOf<Long>()

    @BeforeEach
    fun setup() {
        GlobalMetrics.resetCancellation()
        every { configService.getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }
    }

    @AfterEach
    fun tearDown() {
        GlobalMetrics.resetCancellation()
    }

    private val done = LLMResponse(
        content = """{"response": "Done."}""",
        usage = LLMUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15),
        model = "qwen3.5:9b",
        provider = "ollama",
        cost = 0.0
    )

    private fun refused() = RefioError.LLMConnectionFailed("ollama", "http://localhost:11434", ConnectException("Connection refused"))

    /** The model server's answer at a given moment of virtual time. */
    private fun TestScope.server(answer: (nowMs: Long) -> LLMResponse) {
        val scheduler = testScheduler
        coEvery {
            llmClient.complete(
                provider = any(), model = any(), messages = any(), systemPrompt = any(), maxTokens = any(),
                temperature = any(), responseFormat = any(), thinking = any(), reasoningEffort = any(),
                noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(), subtaskId = any(),
                source = any(), contextContent = any(), systemMessages = any(), kwargs = any()
            )
        } coAnswers {
            callTimes += scheduler.currentTime
            answer(scheduler.currentTime)
        }
    }

    private suspend fun TestScope.call(): LLMResponse {
        val scheduler = testScheduler
        return TurnLLMCaller(
            llmClient,
            configService,
            retryHandler = LLMRetryHandler(llmClient, nowMs = { scheduler.currentTime }),
            ioDispatcher = StandardTestDispatcher(scheduler)
        ).callLLM(
            taskId = "t1",
            mode = TaskMode.AGENT,
            prompt = TurnPrompt("system", listOf(LLMMessage(role = "user", content = "Implement the stubs"))),
            model = "qwen3.5:9b",
            provider = "ollama"
        )
    }

    @Test
    fun `refused connection for 30 seconds is retried until the server answers`() = runTest {
        server { now -> if (now < 30_000) throw refused() else done }

        val response = call()

        assertEquals(done.content, response.content)
        assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L, 30_000L), callTimes, "pauses of 2, 4, 8 and 16 seconds")
    }

    @Test
    fun `bad request fails at once`() = runTest {
        server { throw LLMErrorMapper.fromHttpStatus("ollama", "qwen3.5:9b", 400, "Ollama API error (HTTP 400): invalid request") }

        assertFailsWith<Exception> { call() }
        assertEquals(listOf(0L), callTimes, "the same request would be refused again")
    }

    @Test
    fun `retry after is honoured`() = runTest {
        server { now -> if (now == 0L) throw RefioError.LLMRateLimit("openai", retryAfterMs = 45_000) else done }

        call()

        assertEquals(listOf(0L, 45_000L), callTimes, "the server said when to come back")
    }

    @Test
    fun `retries stop at the total limit`() = runTest {
        every { configService.getTyped(ConfigKeys.LLM_RETRY_MAX_SECONDS, any()) } returns 60L
        server { throw refused() }

        assertFailsWith<RefioError.LLMConnectionFailed> { call() }
        assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L, 30_000L, 60_000L), callTimes, "2 + 4 + 8 + 16 + 30 seconds of pauses fill the limit")
    }

    @Test
    fun `stop during a pause ends the wait at once`() = runTest {
        server { throw refused() }

        val outcome = async { runCatching { call() } }
        advanceTimeBy(3_000) // calls at 0 and 2 s, then a 4-second pause
        GlobalMetrics.requestCancellation()
        advanceTimeBy(1_001)

        assertTrue(outcome.isCompleted, "Stop must not wait out the pause, let alone five minutes of retries")
        assertTrue(outcome.await().exceptionOrNull() is CancellationException, "${outcome.await()}")
        assertEquals(listOf(0L, 2_000L), callTimes, "no call after Stop")
    }
}
