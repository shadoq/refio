package pl.jclab.refio.core.services

import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.core.llm.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val logger = dualLogger("RagEmbeddingService")

/**
 * Service for generating and storing embeddings for RAG.
 *
 * Features:
 * - Generates embeddings for chunks without embeddings
 * - Supports multiple embedding providers (OpenAI, Ollama, etc.)
 * - Progress reporting via Flow
 * - Batch processing with error recovery
 * - Vector serialization (float32, little-endian)
 *
 * Usage:
 * ```
 * val service = RagEmbeddingService(ragRepository, OpenAIEmbeddingProvider())
 * service.generateEmbeddings(projectRoot, "ollama/nomic-embed-text")
 *     .collect { progress ->
 *         println("Progress: ${progress.progressPercent}% - ${progress.statusMessage}")
 *     }
 * ```
 */
class RagEmbeddingService(
    private val ragRepository: RagRepository,
    private val embeddingProvider: EmbeddingProvider
) {
    companion object {
        private const val BATCH_SIZE = 1
        private const val PER_CHUNK_THROTTLE_MS = 200L
        private const val MIN_COOLDOWN_DELAY_MS = 1_000L
    }

    /**
     * Generate embeddings for all chunks without embeddings.
     *
     * @param projectRoot Project root directory (isolates embeddings per project)
     * @param model Embedding model to use
     * @param skipExisting Skip chunks that already have embeddings for this model
     * @return Flow of embedding progress
     */
    fun generateEmbeddings(
        projectRoot: String,
        model: String = "ollama/nomic-embed-text",
        skipExisting: Boolean = true,
        failFastOnUnavailable: Boolean = false
    ): Flow<EmbeddingProgress> = flow {
        logger.info { "Generating embeddings for project=$projectRoot, model=$model" }

        val (provider, modelId) = parseModelString(model)
        val maxInputChars = resolveMaxInputChars(modelId, provider)
        logger.info { "Embedding input clamp for $model (provider=$provider): $maxInputChars chars" }

        // Get chunks without embeddings
        val chunks = if (skipExisting) {
            ragRepository.getChunksWithoutEmbeddings(projectRoot, model)
        } else {
            ragRepository.getChunksForProject(projectRoot)
        }

        val totalChunks = chunks.size

        if (totalChunks == 0) {
            logger.info { "No chunks to embed" }
            emit(EmbeddingProgress(100, "No chunks to embed", 0, 0))
            return@flow
        }

        logger.info { "Found $totalChunks chunks to embed" }

        var processedChunks = 0
        var successCount = 0
        var errorCount = 0

        // Process in batches
        chunks.chunked(BATCH_SIZE).forEach { batch ->
            for (chunk in batch) {
                var shouldEmitStart = true

                while (true) {
                    val progress = if (totalChunks > 0) (processedChunks * 100) / totalChunks else 0

                    if (shouldEmitStart) {
                        emit(EmbeddingProgress(
                            progress,
                            "Embedding chunk ${chunk.id} (${processedChunks + 1}/$totalChunks)...",
                            processedChunks,
                            successCount
                        ))
                    }

                    try {
                        val contentForEmbedding = clampContent(chunk, maxInputChars)
                        val vector = embeddingProvider.generateEmbedding(contentForEmbedding, model)

                        val expectedDimensions = embeddingProvider.getEmbeddingDimensions(model)
                        if (vector.size != expectedDimensions) {
                            logger.warn {
                                "Embedding dimension mismatch: expected=$expectedDimensions, got=${vector.size} for chunk ${chunk.id}"
                            }
                        }

                        ragRepository.createEmbedding(
                            chunkId = chunk.id,
                            model = model,
                            vector = serializeVector(vector),
                            dimensions = vector.size
                        )

                        successCount++
                        processedChunks++

                        if (PER_CHUNK_THROTTLE_MS > 0) {
                            delay(PER_CHUNK_THROTTLE_MS)
                        }
                        break
                    } catch (e: CircuitBreakerOpenException) {
                        if (failFastOnUnavailable) {
                            logger.warn { "Embedding provider unavailable (${e.providerKey}); aborting background embedding run." }
                            emit(EmbeddingProgress(
                                progress,
                                "Provider unavailable, skipping embeddings (background).",
                                processedChunks,
                                successCount
                            ))
                            emit(EmbeddingProgress(
                                100,
                                "Completed with warning: embedding provider unavailable",
                                processedChunks,
                                successCount
                            ))
                            return@flow
                        }

                        val waitMs = e.retryAfterMs.coerceAtLeast(MIN_COOLDOWN_DELAY_MS)
                        logger.warn { "Embedding provider circuit open (${e.providerKey}). Waiting ${waitMs}ms before retry." }

                        emit(EmbeddingProgress(
                            progress,
                            "Provider unavailable, waiting ${(waitMs / 1000).coerceAtLeast(1)}s before retry...",
                            processedChunks,
                            successCount
                        ))

                        delay(waitMs)
                        shouldEmitStart = true  // remind user we're reattempting
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to generate embedding for chunk ${chunk.id}" }
                        errorCount++
                        processedChunks++

                        emit(EmbeddingProgress(
                            progress,
                            "Error embedding chunk ${chunk.id}: ${e.message}",
                            processedChunks,
                            successCount
                        ))

                        if (PER_CHUNK_THROTTLE_MS > 0) {
                            delay(PER_CHUNK_THROTTLE_MS)
                        }
                        break
                    }
                }
            }
        }

        logger.info { "Embeddings generated: $successCount success, $errorCount errors" }

        val statusMessage = if (errorCount > 0) {
            "Completed: $successCount embeddings ($errorCount errors)"
        } else {
            "Completed: $successCount embeddings"
        }

        emit(EmbeddingProgress(100, statusMessage, processedChunks, successCount))
    }.flowOn(Dispatchers.IO)

    private fun parseModelString(model: String): Pair<String?, String> {
        if (model.contains("/")) {
            val parts = model.split("/", limit = 2)
            return Pair(parts[0], parts[1])
        }
        return Pair(null, model)
    }

    private suspend fun resolveMaxInputChars(modelId: String, provider: String?): Int {
        val maxContextTokens = TokenEstimator.getMaxContextForModel(modelId, provider)
        val safeTokens = (maxContextTokens * 0.8).toInt().coerceAtLeast(512)
        return safeTokens * 4
    }

    private fun clampContent(chunk: pl.jclab.refio.core.db.IndexChunk, maxInputChars: Int): String {
        if (chunk.content.length <= maxInputChars) {
            return chunk.content
        }

        val clamped = chunk.content.take(maxInputChars)
        logger.warn {
            "Chunk ${chunk.id} length ${chunk.content.length} exceeds $maxInputChars chars; trimming before embedding. Reindex this file to regenerate smaller chunks."
        }
        return clamped
    }

    /**
     * Serialize float array to byte array (little-endian, float32).
     *
     * Format: Each float is 4 bytes, stored in little-endian byte order.
     * This is compatible with most ML frameworks and databases.
     */
    private fun serializeVector(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    /**
     * Deserialize byte array to float array (little-endian, float32).
     *
     * Inverse of serializeVector.
     */
    fun deserializeVector(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val vector = FloatArray(bytes.size / 4)
        for (i in vector.indices) {
            vector[i] = buffer.getFloat()
        }
        return vector
    }
}

/**
 * Embedding progress data
 */
data class EmbeddingProgress(
    val progressPercent: Int,
    val statusMessage: String,
    val chunksProcessed: Int,
    val successCount: Int
)
