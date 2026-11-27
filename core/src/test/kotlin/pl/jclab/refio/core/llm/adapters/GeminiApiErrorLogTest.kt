package pl.jclab.refio.core.llm.adapters

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.gson.gson
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.DatabaseFactory
import pl.jclab.refio.core.db.repositories.ApiLogRepository
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.testutil.TestDatabase
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * One failed Gemini call must leave one api-log row. The adapter logged the HTTP error where it
 * read the status and then again in its catch-all, so every 503 showed up twice in the API log.
 */
class GeminiApiErrorLogTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb

    @BeforeEach
    fun setup() {
        DatabaseFactory.init(Files.createTempDirectory("refio-gemini-log-test").resolve("test.db").toString())
        db = TestDatabase.createSharedInMemory()
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    @Test
    fun `a streamed Gemini HTTP error is logged once, with the reason Google gave`() = assertLoggedOnce(stream = true)

    @Test
    fun `a plain Gemini HTTP error is logged once, with the reason Google gave`() = assertLoggedOnce(stream = false)

    private fun assertLoggedOnce(stream: Boolean) = runTest {
        val source = "GeminiErrorLog-stream=$stream"
        val llmClient = LLMClient(configService = geminiConfig(), httpClientOverride = overloadedGemini())

        assertFailsWith<RefioError> {
            llmClient.complete(
                provider = "gemini",
                model = "gemini-3.8-flash",
                messages = listOf(LLMMessage(role = "user", content = "hi")),
                stream = stream,
                onChunk = if (stream) { _ -> } else null,
                taskId = "task-1",
                source = source,
            )
        }

        val rows = ApiLogRepository().getRecentLogs(50).filter { it.requestSource == source }
        assertEquals(1, rows.size, "one failed call, one log row")
        assertEquals(503, rows.single().httpStatus)
        assertTrue(rows.single().errorMessage.orEmpty().contains("high demand"), rows.single().errorMessage)
    }

    private fun overloadedGemini() = HttpClient(MockEngine {
        respond(
            content = OVERLOADED,
            status = HttpStatusCode.ServiceUnavailable,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }) { install(ContentNegotiation) { gson() } }

    private fun geminiConfig(): ConfigService {
        val configService = mockk<ConfigService>(relaxed = true)
        every { configService.get(any(), any(), any(), any()) } returns null
        every { configService.get(ConfigKeys.PROVIDER_GEMINI_API_KEY.key, ConfigScope.APP, any(), any()) } returns "test-gemini-key"
        every { configService.getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }
        return configService
    }

    private companion object {
        val OVERLOADED = """
            {"error":{"code":503,"message":"This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later.","status":"UNAVAILABLE"}}
        """.trimIndent()
    }
}
