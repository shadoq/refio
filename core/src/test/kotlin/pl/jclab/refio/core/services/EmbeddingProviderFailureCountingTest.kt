package pl.jclab.refio.core.services

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One failed embedding request must move the circuit breaker by one, not by two.
 *
 * The breaker opens at five failures and then disables RAG for a minute, so counting a single
 * HTTP error twice makes a provider look dead after three errors - a transient 500 or a rate
 * limit burst silently turns off semantic search.
 */
class EmbeddingProviderFailureCountingTest {

    @Test
    fun `three HTTP errors from an OpenAI-compatible endpoint leave the circuit closed`() = runBlocking {
        val baseUrl = "http://localhost:18081/v1"
        val providerKey = "openai_compatible:$baseUrl/embeddings"
        EmbeddingCircuitBreaker.reset(providerKey)
        val requests = AtomicInteger(0)
        val provider = OpenAICompatibleEmbeddingProvider(
            baseUrl = baseUrl,
            httpClientOverride = failingClient(requests),
        )

        try {
            repeat(3) {
                val error = assertFails { runBlocking { provider.generateEmbedding("hello", "m") } }
                assertFalse(
                    error is CircuitBreakerOpenException,
                    "three HTTP errors are below the five-failure threshold, so calls must still be attempted",
                )
            }

            assertEquals("CLOSED", EmbeddingCircuitBreaker.getState(providerKey))
            assertEquals(3, requests.get(), "every attempt must reach the server while the circuit is closed")
        } finally {
            EmbeddingCircuitBreaker.reset(providerKey)
        }
    }

    @Test
    fun `the fifth HTTP error still opens the circuit`() = runBlocking {
        val baseUrl = "http://localhost:18082/v1"
        val providerKey = "openai_compatible:$baseUrl/embeddings"
        EmbeddingCircuitBreaker.reset(providerKey)
        val provider = OpenAICompatibleEmbeddingProvider(
            baseUrl = baseUrl,
            httpClientOverride = failingClient(AtomicInteger(0)),
        )

        try {
            repeat(5) { assertFails { runBlocking { provider.generateEmbedding("hello", "m") } } }

            assertEquals("OPEN", EmbeddingCircuitBreaker.getState(providerKey))
            assertTrue(
                assertFails { runBlocking { provider.generateEmbedding("hello", "m") } } is CircuitBreakerOpenException,
                "an open circuit must fail fast instead of calling the provider again",
            )
        } finally {
            EmbeddingCircuitBreaker.reset(providerKey)
        }
    }

    @Test
    fun `three HTTP errors from Ollama leave the circuit closed`() {
        withFailingOllama { endpoint, requests ->
            val providerKey = "ollama:$endpoint"
            val provider = OllamaEmbeddingProvider(endpoint = endpoint)

            repeat(3) {
                val error = assertFails { runBlocking { provider.generateEmbedding("hello", "nomic-embed-text") } }
                assertFalse(error is CircuitBreakerOpenException)
            }

            assertEquals("CLOSED", EmbeddingCircuitBreaker.getState(providerKey))
            assertEquals(3, requests.get())
        }
    }

    @Test
    fun `three HTTP errors from an Ollama batch call leave the circuit closed`() {
        withFailingOllama { endpoint, requests ->
            val providerKey = "ollama:$endpoint"
            val provider = OllamaEmbeddingProvider(endpoint = endpoint)

            repeat(3) {
                val error = assertFails {
                    runBlocking { provider.generateBatch(listOf("a", "b"), "nomic-embed-text") }
                }
                assertFalse(error is CircuitBreakerOpenException)
            }

            assertEquals("CLOSED", EmbeddingCircuitBreaker.getState(providerKey))
            assertEquals(3, requests.get())
        }
    }

    private fun failingClient(requests: AtomicInteger): HttpClient =
        HttpClient(MockEngine {
            requests.incrementAndGet()
            respond(content = "upstream failure", status = HttpStatusCode.InternalServerError)
        }) { install(ContentNegotiation) { gson() } }

    /**
     * Ollama's provider builds its own HTTP client, so the failure has to come from a real server
     * on a throwaway port. The port also keeps the shared breaker key unique per test.
     */
    private fun withFailingOllama(block: (endpoint: String, requests: AtomicInteger) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger(0)
        server.createContext("/api/embed") { exchange ->
            requests.incrementAndGet()
            val body = "upstream failure".toByteArray()
            exchange.sendResponseHeaders(500, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val endpoint = "http://127.0.0.1:${server.address.port}"
        try {
            block(endpoint, requests)
        } finally {
            server.stop(0)
            EmbeddingCircuitBreaker.reset("ollama:$endpoint")
        }
    }
}
