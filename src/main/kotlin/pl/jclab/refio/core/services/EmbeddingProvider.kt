package pl.jclab.refio.core.services

import com.google.gson.annotations.SerializedName
import com.jetbrains.rd.generator.nova.PredefinedType
import pl.jclab.refio.services.logging.dualLogger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.gson.*

private val logger = dualLogger("EmbeddingProvider")
private const val MIN_PROVIDER_RETRY_DELAY_MS = 1_000L

/**
 * Interface for embedding providers.
 *
 * Providers generate dense vector representations of text
 * for semantic search in RAG systems.
 */
interface EmbeddingProvider {
    /**
     * Generate embedding vector for given text.
     *
     * @param text Text to embed
     * @param model Model to use for embedding
     * @return Float array representing the embedding vector
     * @throws Exception if embedding generation fails
     */
    suspend fun generateEmbedding(text: String, model: String): FloatArray

    suspend fun generateBatch(texts: List<String>, model: String): List<FloatArray> {
        return texts.map { text -> generateEmbedding(text, model) }
    }

    /**
     * Get dimensions of embeddings produced by this model
     */
    fun getEmbeddingDimensions(model: String): Int
}

class CircuitBreakerOpenException(
    val providerKey: String,
    val retryAfterMs: Long,
    cause: Throwable? = null
) : Exception(
    "Embedding provider $providerKey unavailable. Retrying in ${(retryAfterMs / 1000).coerceAtLeast(1)}s.",
    cause
)

private fun computeRetryDelay(providerKey: String): Long {
    val remaining = EmbeddingCircuitBreaker.getCooldownRemaining(providerKey)
    return remaining.coerceAtLeast(MIN_PROVIDER_RETRY_DELAY_MS)
}

/**
 * OpenAI embedding provider using text-embedding models.
 *
 * Supported models:
 * - ollama/nomic-embed-text (1536 dimensions, cheap)
 * - text-embedding-3-large (3072 dimensions, best quality)
 * - text-embedding-ada-002 (1536 dimensions, legacy)
 *
 * Requires OPENAI_API_KEY in system properties.
 */
class OpenAIEmbeddingProvider : EmbeddingProvider {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            gson()
        }
        // Add reasonable timeouts
        engine {
            requestTimeout = 30000  // 30 seconds for API calls
            endpoint {
                connectTimeout = 5000  // 5 seconds to establish connection
                socketTimeout = 25000  // 25 seconds for socket operations
            }
        }
    }
    private val gson = pl.jclab.refio.core.utils.GsonInstance.gson

    companion object {
        private const val OPENAI_API_URL = "https://api.openai.com/v1/embeddings"

        private val MODEL_DIMENSIONS = mapOf(
            "text-embedding-3-small" to 1536,
            "text-embedding-3-large" to 3072,
            "text-embedding-ada-002" to 1536
        )
    }

    override suspend fun generateEmbedding(text: String, model: String): FloatArray {
        val apiKey = System.getProperty("OPENAI_API_KEY")
            ?: throw IllegalStateException("OPENAI_API_KEY not set in system properties")

        val providerKey = "openai:$OPENAI_API_URL"

        // Circuit breaker: fail fast if service is known to be unavailable
        if (!EmbeddingCircuitBreaker.allowCall(providerKey)) {
            throw CircuitBreakerOpenException(providerKey, computeRetryDelay(providerKey))
        }

        logger.debug { "Generating OpenAI embedding with model=$model, text length=${text.length}" }

        try {
            val requestBody = OpenAIEmbeddingRequest(
                input = text,
                model = model
            )

            val response = client.post(OPENAI_API_URL) {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logger.error { "OpenAI embedding API error: ${response.status} - $errorBody" }
                EmbeddingCircuitBreaker.recordFailure(providerKey)
                throw Exception("OpenAI API error: ${response.status}")
            }

            val responseBody = gson.fromJson(response.bodyAsText(), OpenAIEmbeddingResponse::class.java)
            val embedding = responseBody.data.firstOrNull()?.embedding
                ?: throw Exception("No embedding returned from OpenAI API")

            logger.debug { "Generated embedding with ${embedding.size} dimensions" }

            // Success - reset circuit breaker
            EmbeddingCircuitBreaker.recordSuccess(providerKey)

            return embedding.map { it.toFloat() }.toFloatArray()
        } catch (e: CircuitBreakerOpenException) {
            throw e
        } catch (e: Exception) {
            // Record failure and check if we should notify user
            val shouldNotify = EmbeddingCircuitBreaker.recordFailure(providerKey)
            if (shouldNotify) {
                logger.warn { "OpenAI embedding service is unavailable. RAG search will be disabled. Error: ${e.message}" }
            }
            if (EmbeddingCircuitBreaker.getState(providerKey) == "OPEN") {
                throw CircuitBreakerOpenException(providerKey, computeRetryDelay(providerKey), e)
            }
            throw e
        }
    }

    override suspend fun generateBatch(texts: List<String>, model: String): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        val apiKey = System.getProperty("OPENAI_API_KEY")
            ?: throw IllegalStateException("OPENAI_API_KEY not set in system properties")

        val providerKey = "openai:$OPENAI_API_URL"
        if (!EmbeddingCircuitBreaker.allowCall(providerKey)) {
            throw CircuitBreakerOpenException(providerKey, computeRetryDelay(providerKey))
        }

        try {
            val requestBody = OpenAIEmbeddingBatchRequest(
                input = texts,
                model = model
            )

            val response = client.post(OPENAI_API_URL) {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logger.error { "OpenAI embedding batch API error: ${response.status} - $errorBody" }
                EmbeddingCircuitBreaker.recordFailure(providerKey)
                throw Exception("OpenAI API error: ${response.status}")
            }

            val responseBody = gson.fromJson(response.bodyAsText(), OpenAIEmbeddingResponse::class.java)
            val embeddingsByIndex = responseBody.data.associateBy { it.index }
            val embeddings = texts.indices.map { index ->
                embeddingsByIndex[index]?.embedding?.map { it.toFloat() }?.toFloatArray()
                    ?: throw Exception("Missing embedding at index $index from OpenAI API")
            }

            EmbeddingCircuitBreaker.recordSuccess(providerKey)
            return embeddings
        } catch (e: CircuitBreakerOpenException) {
            throw e
        } catch (e: Exception) {
            val shouldNotify = EmbeddingCircuitBreaker.recordFailure(providerKey)
            if (shouldNotify) {
                logger.warn { "OpenAI embedding service is unavailable. Batch embeddings disabled. Error: ${e.message}" }
            }
            if (EmbeddingCircuitBreaker.getState(providerKey) == "OPEN") {
                throw CircuitBreakerOpenException(providerKey, computeRetryDelay(providerKey), e)
            }
            throw e
        }
    }

    override fun getEmbeddingDimensions(model: String): Int {
        return MODEL_DIMENSIONS[model]
            ?: throw IllegalArgumentException("Unknown OpenAI embedding model: $model")
    }
}

/**
 * Ollama embedding provider using local models.
 *
 * Supported models:
 * - nomic-embed-text (768 dimensions, fast)
 * - mxbai-embed-large (1024 dimensions, good quality)
 * - all-minilm (384 dimensions, very fast)
 *
 * Requires Ollama running locally with embedding model pulled.
 */
class OllamaEmbeddingProvider(
    private val endpoint: String = "http://localhost:11434",
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    private val socketTimeoutMs: Long = DEFAULT_SOCKET_TIMEOUT_MS,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS
) : EmbeddingProvider {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            gson()
        }
        // Keep UI responsive but allow longer embedding calls
        engine {
            requestTimeout = requestTimeoutMs  // allow heavier embedding calls
            endpoint {
                connectTimeout = connectTimeoutMs
                socketTimeout = socketTimeoutMs
            }
        }
    }
    private val gson = pl.jclab.refio.core.utils.GsonInstance.gson

    companion object {
        private val MODEL_DIMENSIONS = mapOf(
            "embeddinggemma" to 768,
            "nomic-embed-text" to 768,
            "mxbai-embed-large" to 1024,
            "all-minilm" to 384,
            "all-MiniLM-L6-v2" to 384
        )
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
        private const val DEFAULT_SOCKET_TIMEOUT_MS = 30_000L
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 2_000L
    }

    override suspend fun generateEmbedding(text: String, model: String): FloatArray {
        val providerKey = "ollama:$endpoint"

        // Circuit breaker: fail fast if service is known to be unavailable
        if (!EmbeddingCircuitBreaker.allowCall(providerKey)) {
            throw CircuitBreakerOpenException(providerKey, computeRetryDelay(providerKey))
        }

        logger.debug { "Generating Ollama embedding with model=$model, text length=${text.length}" }

        try {
            val requestBody = OllamaEmbeddingRequest(
                model = model,
                input = text
            )

            val response = OllamaRequestGate.withPermit(endpoint) {
                client.post("$endpoint/api/embed") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logger.error { "Ollama embedding API error: ${response.status} - $errorBody" }
                EmbeddingCircuitBreaker.recordFailure(providerKey)
                throw Exception("Ollama API error: ${response.status}")
            }

            val responseBody = gson.fromJson(response.bodyAsText(), OllamaEmbeddingResponse::class.java)
            val embedding = responseBody.embeddings.firstOrNull()
                ?: throw Exception("No embedding returned from Ollama API")

            logger.info { "Generated embedding with ${embedding.size} dimensions for model ${model} and text size ${text.length}" }

            // Success - reset circuit breaker
            EmbeddingCircuitBreaker.recordSuccess(providerKey)

            return embedding.map { it.toFloat() }.toFloatArray()
        } catch (e: CircuitBreakerOpenException) {
            throw e
        } catch (e: Exception) {
            // Record failure and check if we should notify user
            val shouldNotify = EmbeddingCircuitBreaker.recordFailure(providerKey)
            if (shouldNotify) {
                logger.warn { "Ollama service at $endpoint is unavailable. RAG search will be disabled. Error: ${e.message}" }
            }
            if (EmbeddingCircuitBreaker.getState(providerKey) == "OPEN") {
                throw CircuitBreakerOpenException(providerKey, computeRetryDelay(providerKey), e)
            }
            throw e
        }
    }

    override suspend fun generateBatch(texts: List<String>, model: String): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        val providerKey = "ollama:$endpoint"
        if (!EmbeddingCircuitBreaker.allowCall(providerKey)) {
            throw CircuitBreakerOpenException(providerKey, computeRetryDelay(providerKey))
        }

        try {
            val requestBody = OllamaEmbeddingBatchRequest(
                model = model,
                input = texts
            )

            val response = OllamaRequestGate.withPermit(endpoint) {
                client.post("$endpoint/api/embed") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logger.error { "Ollama embedding batch API error: ${response.status} - $errorBody" }
                EmbeddingCircuitBreaker.recordFailure(providerKey)
                throw Exception("Ollama API error: ${response.status}")
            }

            val responseBody = gson.fromJson(response.bodyAsText(), OllamaEmbeddingResponse::class.java)
            val embeddings = responseBody.embeddings.map { vector ->
                vector.map { it.toFloat() }.toFloatArray()
            }

            if (embeddings.size != texts.size) {
                throw Exception("Expected ${texts.size} embeddings from Ollama, got ${embeddings.size}")
            }

            EmbeddingCircuitBreaker.recordSuccess(providerKey)
            return embeddings
        } catch (e: CircuitBreakerOpenException) {
            throw e
        } catch (e: Exception) {
            val shouldNotify = EmbeddingCircuitBreaker.recordFailure(providerKey)
            if (shouldNotify) {
                logger.warn { "Ollama service at $endpoint is unavailable. Batch embeddings disabled. Error: ${e.message}" }
            }
            if (EmbeddingCircuitBreaker.getState(providerKey) == "OPEN") {
                throw CircuitBreakerOpenException(providerKey, computeRetryDelay(providerKey), e)
            }
            throw e
        }
    }

    override fun getEmbeddingDimensions(model: String): Int {
        return MODEL_DIMENSIONS[model]
            ?: 768  // Default to common dimension size
    }
}

// ========== DTOs ==========

// OpenAI DTOs
private data class OpenAIEmbeddingRequest(
    val input: String,
    val model: String
)

private data class OpenAIEmbeddingBatchRequest(
    val input: List<String>,
    val model: String
)

private data class OpenAIEmbeddingResponse(
    val data: List<OpenAIEmbeddingData>,
    val model: String,
    val usage: OpenAIUsage
)

private data class OpenAIEmbeddingData(
    val embedding: List<Double>,
    val index: Int
)

private data class OpenAIUsage(
    @SerializedName("prompt_tokens") val promptTokens: Int,
    @SerializedName("total_tokens") val totalTokens: Int
)

// Ollama DTOs
private data class OllamaEmbeddingRequest(
    val model: String,
    val input: String
)

private data class OllamaEmbeddingBatchRequest(
    val model: String,
    val input: List<String>
)

private data class OllamaEmbeddingResponse(
    val model: String,
    val embeddings: List<DoubleArray>,
    val totalDuration: Long,
    val loadDuration: Long,
    val promptEvalCount: Long
)
