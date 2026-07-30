package pl.jclab.refio.core.services

import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.gson.gson
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.llm.NoEgressViolationException
import pl.jclab.refio.core.security.NetworkPolicy
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Lets a self-hosted embedding model (llama.cpp, vLLM, text-embeddings-inference) be used for RAG.
 * Before this existed the only options were api.openai.com at a fixed URL, or Ollama's own
 * `/api/embed` protocol, which an OpenAI-compatible server does not serve.
 */
class OpenAICompatibleEmbeddingProviderTest {

    @Test
    fun `embeddings are requested in the OpenAI shape from the configured endpoint`() = runTest {
        var requestUrl: String? = null
        var requestBody: String? = null
        val provider = OpenAICompatibleEmbeddingProvider(
            baseUrl = "http://localhost:8081/v1",
            httpClientOverride = mockClient(
                onRequest = { url, body -> requestUrl = url; requestBody = body },
                responseJson = embeddingResponse(listOf(listOf(0.5, -0.25))),
            ),
        )

        val vectors = provider.generateBatch(listOf("hello"), "jina-embeddings-v5")

        assertEquals("http://localhost:8081/v1/embeddings", requestUrl)
        val body = JsonParser.parseString(requestBody!!).asJsonObject
        assertEquals("jina-embeddings-v5", body.get("model").asString)
        assertEquals("hello", body.getAsJsonArray("input").first().asString)
        assertContentEquals(floatArrayOf(0.5f, -0.25f), vectors.single())
    }

    @Test
    fun `a trailing slash on the base URL does not produce a double slash`() = runTest {
        var requestUrl: String? = null
        val provider = OpenAICompatibleEmbeddingProvider(
            baseUrl = "http://localhost:8081/v1/",
            httpClientOverride = mockClient(
                onRequest = { url, _ -> requestUrl = url },
                responseJson = embeddingResponse(listOf(listOf(1.0))),
            ),
        )

        provider.generateEmbedding("hello", "jina-embeddings-v5")

        assertEquals("http://localhost:8081/v1/embeddings", requestUrl)
    }

    @Test
    fun `no Authorization header is sent when the local server needs no key`() = runTest {
        var authHeader: String? = "unset"
        val client = HttpClient(MockEngine { request ->
            authHeader = request.headers[HttpHeaders.Authorization]
            respond(
                content = embeddingResponse(listOf(listOf(1.0))),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }) { install(ContentNegotiation) { gson() } }

        OpenAICompatibleEmbeddingProvider(baseUrl = "http://localhost:8081/v1", httpClientOverride = client)
            .generateEmbedding("hello", "jina-embeddings-v5")

        assertNull(authHeader, "sending an empty bearer token makes some servers reject the call")
    }

    @Test
    fun `vectors keep their request order even when the server omits the index field`() = runTest {
        // Not every OpenAI-compatible server fills `index`; dropping the response over that would
        // fail an otherwise valid batch.
        val provider = OpenAICompatibleEmbeddingProvider(
            baseUrl = "http://localhost:8081/v1",
            httpClientOverride = mockClient(
                onRequest = { _, _ -> },
                responseJson = """
                    {"data":[{"embedding":[1.0]},{"embedding":[2.0]}],"model":"m","usage":{"prompt_tokens":1,"total_tokens":1}}
                """.trimIndent(),
            ),
        )

        val vectors = provider.generateBatch(listOf("first", "second"), "m")

        assertContentEquals(floatArrayOf(1.0f), vectors[0])
        assertContentEquals(floatArrayOf(2.0f), vectors[1])
    }

    @Test
    fun `no-egress blocks a remote embeddings endpoint`() = runTest {
        val provider = OpenAICompatibleEmbeddingProvider(
            baseUrl = "https://api.somewhere.example/v1",
            networkPolicy = NetworkPolicy(noEgressConfig(enabled = true)),
            httpClientOverride = mockClient({ _, _ -> }, embeddingResponse(listOf(listOf(1.0)))),
        )

        assertFailsWith<NoEgressViolationException> {
            provider.generateEmbedding("hello", "jina-embeddings-v5")
        }
    }

    @Test
    fun `no-egress still allows a local embeddings endpoint`() = runTest {
        // The whole point of a local model is that no-egress must not disable it.
        val provider = OpenAICompatibleEmbeddingProvider(
            baseUrl = "http://localhost:8081/v1",
            networkPolicy = NetworkPolicy(noEgressConfig(enabled = true)),
            httpClientOverride = mockClient({ _, _ -> }, embeddingResponse(listOf(listOf(1.0)))),
        )

        assertContentEquals(floatArrayOf(1.0f), provider.generateEmbedding("hello", "jina-embeddings-v5"))
    }

    @Test
    fun `the vector width is learned from the response instead of guessed`() = runTest {
        val provider = OpenAICompatibleEmbeddingProvider(
            baseUrl = "http://localhost:8081/v1",
            httpClientOverride = mockClient({ _, _ -> }, embeddingResponse(listOf(listOf(1.0, 2.0, 3.0)))),
        )

        assertFailsWith<IllegalStateException>("an arbitrary model has no width until it answers") {
            provider.getEmbeddingDimensions("jina-embeddings-v5")
        }

        provider.generateEmbedding("hello", "jina-embeddings-v5")

        assertEquals(3, provider.getEmbeddingDimensions("jina-embeddings-v5"))
    }

    private fun embeddingResponse(vectors: List<List<Double>>): String {
        val data = vectors.mapIndexed { index, vector ->
            """{"embedding":[${vector.joinToString(",")}],"index":$index}"""
        }.joinToString(",")
        return """{"data":[$data],"model":"m","usage":{"prompt_tokens":1,"total_tokens":1}}"""
    }

    private fun mockClient(
        onRequest: (url: String, body: String) -> Unit,
        responseJson: String,
    ): HttpClient = HttpClient(MockEngine { request ->
        onRequest(request.url.toString(), (request.body as TextContent).text)
        respond(
            content = responseJson,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }) { install(ContentNegotiation) { gson() } }

    private fun noEgressConfig(enabled: Boolean) = mockk<ConfigService>().also {
        every { it.getTyped<Boolean>(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, any()) } returns enabled
    }
}
