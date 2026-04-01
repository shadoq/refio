package pl.jclab.refio.core.services

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.llm.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import pl.jclab.refio.core.db.IndexChunk
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

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
    private val embeddingProvider: EmbeddingProvider,
    private val configService: ConfigService? = null
) {
    companion object {
        private const val BATCH_SIZE = ConfigService.DEFAULT_RAG_EMBEDDING_BATCH_SIZE
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

        // Get chunks without embeddings, plus filter out unchanged chunks via delta check
        val chunks = if (skipExisting) {
            ragRepository.getChunksWithoutEmbeddings(projectRoot, model)
        } else {
            // When not skipping existing, filter out chunks whose content hasn't changed
            val allChunks = ragRepository.getChunksForProject(projectRoot)
            val existingEmbeddingChunkIds = ragRepository.getEmbeddingChunkIds(projectRoot, model)
            allChunks.filter { chunk ->
                if (chunk.id in existingEmbeddingChunkIds) {
                    needsReembedding(chunk, model)
                } else {
                    true
                }
            }
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

        val batchSize = configService?.getTyped<Int>(ConfigKeys.RAG_EMBEDDINGS_BATCH_SIZE) ?: BATCH_SIZE

        chunks.chunked(batchSize).forEach { batch ->
            val preparedBatch = batch.map { chunk ->
                chunk to clampContent(chunk, maxInputChars)
            }

            val batchProgress = if (totalChunks > 0) (processedChunks * 100) / totalChunks else 0
            emit(EmbeddingProgress(
                batchProgress,
                "Embedding batch starting at chunk ${batch.first().id} (${processedChunks + 1}/$totalChunks)...",
                processedChunks,
                successCount
            ))

            try {
                val vectors = embeddingProvider.generateBatch(
                    preparedBatch.map { it.second },
                    model
                )

                if (vectors.size != preparedBatch.size) {
                    throw IllegalStateException("Embedding batch returned ${vectors.size} vectors for ${preparedBatch.size} chunks")
                }

                preparedBatch.zip(vectors).forEach { (chunkWithContent, vector) ->
                    val (chunk, _) = chunkWithContent
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
                }

                if (PER_CHUNK_THROTTLE_MS > 0) {
                    delay(PER_CHUNK_THROTTLE_MS)
                }
                return@forEach
            } catch (e: CircuitBreakerOpenException) {
                if (failFastOnUnavailable) {
                    logger.warn { "Embedding provider unavailable (${e.providerKey}); aborting background embedding run." }
                    emit(EmbeddingProgress(
                        batchProgress,
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
            } catch (e: Exception) {
                logger.warn(e) { "Batch embedding failed for ${batch.size} chunks, falling back to sequential processing" }
            }

            for ((chunk, contentForEmbedding) in preparedBatch) {
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

    /**
     * Check if a chunk needs re-embedding by comparing its current contentHash
     * against the stored contentHash at the time the embedding was created.
     *
     * Returns true if the embedding should be regenerated (content has changed),
     * false if the existing embedding is still valid.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun needsReembedding(chunk: IndexChunk, _model: String): Boolean {
        val storedHash = ragRepository.getChunkContentHash(chunk.id) ?: return true
        val currentHash = calculateContentHash(chunk.content)
        return storedHash != currentHash
    }

    private fun calculateContentHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
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
