package pl.jclab.refio.core.llm.adapters

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
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
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.DatabaseFactory
import pl.jclab.refio.core.db.repositories.ApiLogRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.testutil.TestDatabase
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * Regression for the pooled-adapter attribution bug.
 *
 * `LLMClient` reuses one HttpClient per `provider:model`, but the per-request attribution
 * (`taskId`/`subtaskId`/`source`) must NOT be reused with it. The previous design pooled the
 * whole adapter, so the FIRST request's identity was baked into every later one — a PLAN turn
 * or a title-generation call reusing the `Chat` adapter logged `source="Chat"` and was attributed
 * to an earlier session's task. This drives two calls through the SAME pooled client and asserts
 * each api-log row carries its own call's identity.
 */
class AdapterCallMetadataAttributionTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb

    @BeforeEach
    fun setup() {
        // DualLogger only persists api_logs when DatabaseFactory is initialized (global singleton,
        // idempotent). createSharedInMemory() then becomes the most-recent Exposed connection, so the
        // adapter's write and our read both resolve to the same in-memory DB via transaction{}.
        DatabaseFactory.init(Files.createTempDirectory("refio-attr-test").resolve("test.db").toString())
        db = TestDatabase.createSharedInMemory()
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    @Test
    fun `each call is logged under its own source and task, despite the shared pooled client`() = runTest {
        val configService = mockProviderConfig(
            key = ConfigKeys.PROVIDER_OPENAI_API_KEY.key,
            apiKey = "test-openai-key"
        )
        // Single mock transport shared across both calls — proving the client is pooled while the
        // attribution is not.
        val llmClient = LLMClient(
            configService = configService,
            httpClientOverride = mockHttpClient { respondJson(OPENAI_OK) }
        )

        llmClient.complete(
            provider = "openai",
            model = "gpt-4o-mini",
            messages = listOf(LLMMessage(role = "user", content = "first")),
            taskId = "task-1",
            subtaskId = "sub-1",
            source = "FirstSource"
        )
        llmClient.complete(
            provider = "openai",
            model = "gpt-4o-mini",
            messages = listOf(LLMMessage(role = "user", content = "second")),
            taskId = "task-2",
            subtaskId = "sub-2",
            source = "SecondSource"
        )

        val logs = ApiLogRepository().getRecentLogs(2)
        val bySource = logs.associateBy { it.requestSource }

        assertEquals(setOf("FirstSource", "SecondSource"), bySource.keys, "both calls must keep distinct sources")
        assertEquals("task-1", bySource["FirstSource"]?.taskId)
        assertEquals("sub-1", bySource["FirstSource"]?.subtaskId)
        assertEquals("task-2", bySource["SecondSource"]?.taskId)
        assertEquals("sub-2", bySource["SecondSource"]?.subtaskId)
    }

    private fun mockProviderConfig(key: String, apiKey: String): ConfigService {
        val configService = mockk<ConfigService>(relaxed = true)
        every { configService.get(any(), any(), any(), any()) } returns null
        every { configService.get(key, ConfigScope.APP, any(), any()) } returns apiKey
        every { configService.getTyped(ConfigKeys.API_CALL_TIMEOUT, any()) } returns ConfigKeys.API_CALL_TIMEOUT.default
        every { configService.getTyped(ConfigKeys.MAX_OUTPUT_SIZE, any()) } returns ConfigKeys.MAX_OUTPUT_SIZE.default
        return configService
    }

    private fun mockHttpClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): HttpClient {
        return HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                gson {
                    serializeNulls()
                }
            }
        }
    }

    private suspend fun MockRequestHandleScope.respondJson(json: String) =
        respond(
            content = json,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        )

    private companion object {
        val OPENAI_OK = """
            {
              "id": "chatcmpl_test",
              "model": "gpt-4o-mini",
              "choices": [
                { "message": { "role": "assistant", "content": "ok" }, "finish_reason": "stop" }
              ],
              "usage": { "prompt_tokens": 5, "completion_tokens": 2, "total_tokens": 7 }
            }
        """.trimIndent()
    }
}
