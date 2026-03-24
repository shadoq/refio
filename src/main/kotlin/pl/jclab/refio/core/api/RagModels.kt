package pl.jclab.refio.core.api

import pl.jclab.refio.core.db.RagContentType

/**
 * RAG DTOs used by RagRouter and UI components.
 * Extracted to core/api/ for platform independence.
 */
data class RagIndexedFileDto(
    val id: Int,
    val filePath: String,
    val chunksCount: Int,
    val embeddingsCount: Int,
    val fileSize: Long,
    val contentType: RagContentType,
    val indexedAt: Long
)

data class RagStatisticsDto(
    val filesCount: Int,
    val chunksCount: Int,
    val embeddingsCount: Int
)

data class RagChunkDto(
    val id: Int,
    val chunkIndex: Int,
    val content: String,
    val startLine: Int?,
    val endLine: Int?
)
