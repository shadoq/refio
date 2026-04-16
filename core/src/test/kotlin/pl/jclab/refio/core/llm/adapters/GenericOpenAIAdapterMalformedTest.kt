package pl.jclab.refio.core.llm.adapters

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.services.ConfigService
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenericOpenAIAdapterMalformedTest {

    @Test
    fun `chat completions without choices array throws MalformedResponse`() = runTest {
        val config = mockConfig()
        val fixture = """{"foo":"bar"}"""

        val adapter = GenericOpenAIAdapter(
            model = "gpt-test",
            providerName = "generic_openai",
            configService = config,
            baseUrlOverride = "https://mock.test/v1",
            httpClientOverride = mockHttpClient(fixture)
        )

        val err = assertThrows<RefioError.MalformedResponse> {
            adapter.chat(
                messages = listOf(LLMMessage(role = "user", content = "hi")),
                systemMessages = emptyList(),
                maxTokens = 64,
                temperature = 0.0,
                streaming = false,
                onStreamChunk = null,
                kwargs = emptyMap()
            )
        }
        assertEquals("generic_openai", err.provider)
        assertTrue(err.bodyPreview.contains("foo"), "preview should contain raw body: ${err.bodyPreview}")
        assertTrue(err.reason.contains("choices"), "reason should mention 'choices': ${err.reason}")
    }

    private fun mockConfig(): ConfigService {
        val config = mockk<ConfigService>()
        every { config.get(any(), any(), any(), any()) } returns null
        every { config.getTyped(ConfigKeys.API_CALL_TIMEOUT, any()) } returns ConfigKeys.API_CALL_TIMEOUT.default
        every { config.getTyped(ConfigKeys.MAX_OUTPUT_SIZE, any()) } returns ConfigKeys.MAX_OUTPUT_SIZE.default
        every { config.getTyped(ConfigKeys.PROVIDER_CUSTOM_OPENAI_BASE_URL) } returns "https://mock.test/v1"
        every { config.getTyped(ConfigKeys.PROVIDER_CUSTOM_OPENAI_API_KEY) } returns "test-key"
        return config
    }

    private fun mockHttpClient(responseJson: String): HttpClient {
        return HttpClient(MockEngine { _ ->
            respond(
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }) {
            install(ContentNegotiation) {
                gson {
                    serializeNulls()
                }
            }
        }
    }
}
