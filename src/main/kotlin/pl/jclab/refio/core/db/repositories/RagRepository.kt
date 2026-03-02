package pl.jclab.refio.core.db.repositories

import pl.jclab.refio.core.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest

/**
 * Repository for RAG (Retrieval-Augmented Generation) operations.
 *
 * Provides CRUD operations for:
 * - Indexed files
 * - Chunks
 * - Embeddings
 *
 * All operations are isolated per project via projectRoot (not taskId).
 */
class RagRepository {

    // ========== Indexed Files Operations ==========

    /**
     * Create indexed file record
     */
    fun createIndexedFile(
        projectRoot: String,
        filePath: String,
        fileHash: String,
        checksum: String? = null,
        fileSize: Long,
        mimeType: String?,
        lastModified: Long,
        contentType: RagContentType = RagContentType.PROJECT_CODE,
        sourceUrl: String? = null,
        metadata: String? = null
    ): Int = transaction {
        IndexFilesTable.insertAndGetId {
            it[IndexFilesTable.projectRoot] = projectRoot
            it[IndexFilesTable.filePath] = filePath
            it[IndexFilesTable.fileHash] = fileHash
            it[IndexFilesTable.fileSize] = fileSize
            it[IndexFilesTable.checksum] = checksum
            it[IndexFilesTable.mimeType] = mimeType
            it[IndexFilesTable.lastModified] = lastModified
            it[IndexFilesTable.contentType] = contentType
            it[IndexFilesTable.sourceUrl] = sourceUrl
            it[IndexFilesTable.metadata] = metadata
        }.value
    }

    /**
     * Update indexed file (for incremental reindexing)
     */
    fun updateIndexedFile(
        fileId: Int,
        fileHash: String,
        checksum: String? = null,
        fileSize: Long,
        lastModified: Long,
        metadata: String? = null
    ): Int = transaction {
        IndexFilesTable.update({ IndexFilesTable.id eq fileId }) {
            it[IndexFilesTable.fileHash] = fileHash
            it[IndexFilesTable.checksum] = checksum
            it[IndexFilesTable.fileSize] = fileSize
            it[IndexFilesTable.lastModified] = lastModified
            it[indexedAt] = System.currentTimeMillis()
            if (metadata != null) {
                it[IndexFilesTable.metadata] = metadata
            }
        }
        fileId
    }

    /**
     * Update indexed file metadata JSON (nullable to allow clearing).
     */
    fun updateIndexedFileMetadata(fileId: Int, metadata: String?) = transaction {
        IndexFilesTable.update({ IndexFilesTable.id eq fileId }) {
            it[IndexFilesTable.metadata] = metadata
        }
    }

    /**
     * Get indexed files for project (isolated per project root)
     */
    fun getIndexedFiles(
        projectRoot: String,
        contentType: RagContentType? = null
    ): List<IndexFile> = transaction {
        var query = IndexFilesTable.selectAll().where { IndexFilesTable.projectRoot eq projectRoot }

        if (contentType != null) {
            query = query.andWhere { IndexFilesTable.contentType eq contentType }
        }

        query.map { mapIndexFile(it) }
    }

    /**
     * Get indexed file by ID
     */
    fun getIndexedFile(fileId: Int): IndexFile? = transaction {
        IndexFilesTable.selectAll().where { IndexFilesTable.id eq fileId }
            .map { mapIndexFile(it) }
            .firstOrNull()
    }

    /**
     * Get indexed files by IDs in batch (OPTIMIZATION for RAG search)
     * Fetches multiple files in a single query instead of N individual queries
     */
    fun getFilesBatch(fileIds: List<Int>): List<IndexFile> = transaction {
        if (fileIds.isEmpty()) {
            return@transaction emptyList()
        }

        IndexFilesTable.selectAll().where { IndexFilesTable.id inList fileIds }
            .map { mapIndexFile(it) }
    }

    /**
     * Get indexed file by path (for checking if already indexed)
     */
    fun getIndexedFileByPath(projectRoot: String, filePath: String): IndexFile? = transaction {
        IndexFilesTable.selectAll()
            .where { (IndexFilesTable.projectRoot eq projectRoot) and (IndexFilesTable.filePath eq filePath) }
            .map { mapIndexFile(it) }
            .firstOrNull()
    }

    /**
     * Get indexed files by source URLs (for documentation)
     */
    fun getIndexedFilesBySourceUrls(sourceUrls: List<String>): List<IndexFile> = transaction {
        if (sourceUrls.isEmpty()) {
            return@transaction emptyList()
        }

        IndexFilesTable.selectAll()
            .where { IndexFilesTable.sourceUrl inList sourceUrls }
            .orderBy(IndexFilesTable.indexedAt to SortOrder.DESC)
            .map { mapIndexFile(it) }
    }

    /**
     * Delete indexed file and cascading chunks/embeddings
     */
    fun deleteIndexedFile(fileId: Int) = transaction {
        IndexFilesTable.deleteWhere { IndexFilesTable.id eq fileId }
    }

    /**
     * Delete all indexed files for project (cleanup)
     */
    fun deleteIndexedFilesForProject(projectRoot: String) = transaction {
        IndexFilesTable.deleteWhere { IndexFilesTable.projectRoot eq projectRoot }
    }

    /**
     * Delete indexed files by sourceUrl (for documentation cleanup)
     */
    fun deleteIndexedFilesBySourceUrl(sourceUrl: String) = transaction {
        IndexFilesTable.deleteWhere {
            (contentType eq RagContentType.DOCUMENTATION) and
            (IndexFilesTable.sourceUrl eq sourceUrl)
        }
    }

    // ========== Chunks Operations ==========

    /**
     * Create chunk
     */
    fun createChunk(
        fileId: Int,
        chunkIndex: Int,
        content: String,
        startLine: Int?,
        endLine: Int?,
        startChar: Int? = null,
        endChar: Int? = null,
        metadata: String? = null
    ): Int = transaction {
        val contentHash = calculateContentHash(content)

        IndexChunksTable.insertAndGetId {
            it[IndexChunksTable.fileId] = fileId
            it[IndexChunksTable.chunkIndex] = chunkIndex
            it[IndexChunksTable.content] = content
            it[IndexChunksTable.contentHash] = contentHash
            it[IndexChunksTable.startLine] = startLine
            it[IndexChunksTable.endLine] = endLine
            it[IndexChunksTable.startChar] = startChar
            it[IndexChunksTable.endChar] = endChar
            it[IndexChunksTable.metadata] = metadata
        }.value
    }

    /**
     * Update chunk metadata JSON (nullable to allow clearing).
     */
    fun updateChunkMetadata(chunkId: Int, metadata: String?) = transaction {
        IndexChunksTable.update({ IndexChunksTable.id eq chunkId }) {
            it[IndexChunksTable.metadata] = metadata
        }
    }

    /**
     * Get chunk by ID
     */
    fun getChunk(chunkId: Int): IndexChunk? = transaction {
        IndexChunksTable.selectAll().where { IndexChunksTable.id eq chunkId }
            .map { mapIndexChunk(it) }
            .firstOrNull()
    }

    /**
     * Get chunks by IDs in batch (OPTIMIZATION for RAG search)
     * Fetches multiple chunks in a single query instead of N individual queries
     */
    fun getChunksBatch(chunkIds: List<Int>): List<IndexChunk> = transaction {
        if (chunkIds.isEmpty()) {
            return@transaction emptyList()
        }

        IndexChunksTable.selectAll().where { IndexChunksTable.id inList chunkIds }
            .map { mapIndexChunk(it) }
    }

    /**
     * Get chunks for file
     */
    fun getChunksForFile(fileId: Int): List<IndexChunk> = transaction {
        IndexChunksTable.selectAll().where { IndexChunksTable.fileId eq fileId }
            .orderBy(IndexChunksTable.chunkIndex to SortOrder.ASC)
            .map { mapIndexChunk(it) }
    }

    /**
     * Get chunks for project
     */
    fun getChunksForProject(projectRoot: String): List<IndexChunk> = transaction {
        IndexChunksTable
            .innerJoin(IndexFilesTable, { fileId }, { IndexFilesTable.id })
            .selectAll().where { IndexFilesTable.projectRoot eq projectRoot }
            .map { mapIndexChunk(it) }
    }

    /**
     * Get chunks without embeddings for specific model
     */
    fun getChunksWithoutEmbeddings(projectRoot: String, model: String): List<IndexChunk> = transaction {
        // Get all chunk IDs that already have embeddings for this model
        val chunksWithEmbeddings = EmbeddingsTable
            .selectAll()
            .where { EmbeddingsTable.model eq model }
            .map { it[EmbeddingsTable.chunkId] }
            .toSet()

        // Get chunks for project that don't have embeddings yet
        IndexChunksTable
            .innerJoin(IndexFilesTable, { fileId }, { IndexFilesTable.id })
            .selectAll().where {
                (IndexFilesTable.projectRoot eq projectRoot) and
                        (IndexChunksTable.id notInList chunksWithEmbeddings)
            }
            .map { mapIndexChunk(it) }
    }

    /**
     * Delete chunks for file (for reindexing)
     */
    fun deleteChunksForFile(fileId: Int) = transaction {
        IndexChunksTable.deleteWhere { IndexChunksTable.fileId eq fileId }
    }

    /**
     * Delete all chunks for project (cleanup)
     */
    fun deleteChunksForProject(projectRoot: String) = transaction {
        val fileIds = IndexFilesTable
            .selectAll()
            .where { IndexFilesTable.projectRoot eq projectRoot }
            .map { it[IndexFilesTable.id].value }

        if (fileIds.isNotEmpty()) {
            IndexChunksTable.deleteWhere { fileId inList fileIds }
        }
    }

    // ========== Embeddings Operations ==========

    /**
     * Create embedding
     */
    fun createEmbedding(
        chunkId: Int,
        model: String,
        vector: ByteArray,
        dimensions: Int
    ): Int = transaction {
        EmbeddingsTable.insertAndGetId {
            it[EmbeddingsTable.chunkId] = chunkId
            it[EmbeddingsTable.model] = model
            it[EmbeddingsTable.vector] = ExposedBlob(vector)
            it[EmbeddingsTable.dimensions] = dimensions
        }.value
    }

    /**
     * Get embedding for chunk and model
     */
    fun getEmbedding(chunkId: Int, model: String): Embedding? = transaction {
        EmbeddingsTable.selectAll().where { (EmbeddingsTable.chunkId eq chunkId) and (EmbeddingsTable.model eq model) }
            .map { mapEmbedding(it) }
            .firstOrNull()
    }

    /**
     * Get all embeddings for project and model (for search)
     */
    fun getEmbeddings(
        projectRoot: String,
        model: String,
        contentType: RagContentType? = null
    ): List<Embedding> = transaction {
        val query = EmbeddingsTable
            .innerJoin(IndexChunksTable, { chunkId }, { IndexChunksTable.id })
            .innerJoin(IndexFilesTable, { IndexChunksTable.fileId }, { IndexFilesTable.id })
            .selectAll().where { (IndexFilesTable.projectRoot eq projectRoot) and (EmbeddingsTable.model eq model) }

        val filtered = if (contentType != null) {
            query.andWhere { IndexFilesTable.contentType eq contentType }
        } else {
            query
        }

        filtered.map { row ->
            Embedding(
                id = row[EmbeddingsTable.id].value,
                chunkId = row[EmbeddingsTable.chunkId],
                model = row[EmbeddingsTable.model],
                vector = row[EmbeddingsTable.vector].bytes,
                dimensions = row[EmbeddingsTable.dimensions],
                createdAt = row[EmbeddingsTable.createdAt]
            )
        }
    }

    /**
     * Get embeddings for file
     */
    fun getEmbeddingsForFile(fileId: Int, model: String? = null): List<Embedding> = transaction {
        val chunks = IndexChunksTable
            .selectAll()
            .where { IndexChunksTable.fileId eq fileId }
            .map { it[IndexChunksTable.id].value }

        if (chunks.isEmpty()) {
            return@transaction emptyList()
        }

        var query = EmbeddingsTable.selectAll().where { EmbeddingsTable.chunkId inList chunks }

        if (model != null) {
            query = query.andWhere { EmbeddingsTable.model eq model }
        }

        query.map { mapEmbedding(it) }
    }

    /**
     * Delete embeddings for chunk
     */
    fun deleteEmbeddingsForChunk(chunkId: Int) = transaction {
        EmbeddingsTable.deleteWhere { EmbeddingsTable.chunkId eq chunkId }
    }

    /**
     * Delete all embeddings for project (cleanup)
     */
    fun deleteEmbeddingsForProject(projectRoot: String) = transaction {
        val chunkIds = IndexChunksTable
            .innerJoin(IndexFilesTable, { fileId }, { IndexFilesTable.id })
            .selectAll()
            .where { IndexFilesTable.projectRoot eq projectRoot }
            .map { it[IndexChunksTable.id].value }

        if (chunkIds.isNotEmpty()) {
            EmbeddingsTable.deleteWhere { chunkId inList chunkIds }
        }
    }

    // ========== Statistics ==========

    /**
     * Get RAG statistics for project
     */
    fun getStatistics(projectRoot: String): RagStatistics = transaction {
        val filesCount = IndexFilesTable
            .selectAll().where { IndexFilesTable.projectRoot eq projectRoot }
            .count()

        val chunksCount = IndexChunksTable
            .innerJoin(IndexFilesTable, { fileId }, { IndexFilesTable.id })
            .selectAll().where { IndexFilesTable.projectRoot eq projectRoot }
            .count()

        val embeddingsCount = EmbeddingsTable
            .innerJoin(IndexChunksTable, { chunkId }, { IndexChunksTable.id })
            .innerJoin(IndexFilesTable, { IndexChunksTable.fileId }, { IndexFilesTable.id })
            .selectAll().where { IndexFilesTable.projectRoot eq projectRoot }
            .count()

        RagStatistics(
            filesCount = filesCount.toInt(),
            chunksCount = chunksCount.toInt(),
            embeddingsCount = embeddingsCount.toInt()
        )
    }

    // ========== Helper Methods ==========

    private fun mapIndexFile(row: ResultRow) = IndexFile(
        id = row[IndexFilesTable.id].value,
        projectRoot = row[IndexFilesTable.projectRoot],
        filePath = row[IndexFilesTable.filePath],
        fileHash = row[IndexFilesTable.fileHash],
        checksum = row[IndexFilesTable.checksum],
        fileSize = row[IndexFilesTable.fileSize],
        mimeType = row[IndexFilesTable.mimeType],
        metadata = row[IndexFilesTable.metadata],
        contentType = row[IndexFilesTable.contentType],
        sourceUrl = row[IndexFilesTable.sourceUrl],
        indexedAt = row[IndexFilesTable.indexedAt],
        lastModified = row[IndexFilesTable.lastModified]
    )

    private fun mapIndexChunk(row: ResultRow) = IndexChunk(
        id = row[IndexChunksTable.id].value,
        fileId = row[IndexChunksTable.fileId],
        chunkIndex = row[IndexChunksTable.chunkIndex],
        content = row[IndexChunksTable.content],
        contentHash = row[IndexChunksTable.contentHash],
        metadata = row[IndexChunksTable.metadata],
        startLine = row[IndexChunksTable.startLine],
        endLine = row[IndexChunksTable.endLine],
        startChar = row[IndexChunksTable.startChar],
        endChar = row[IndexChunksTable.endChar],
        createdAt = row[IndexChunksTable.createdAt]
    )

    private fun mapEmbedding(row: ResultRow) = Embedding(
        id = row[EmbeddingsTable.id].value,
        chunkId = row[EmbeddingsTable.chunkId],
        model = row[EmbeddingsTable.model],
        vector = row[EmbeddingsTable.vector].bytes,
        dimensions = row[EmbeddingsTable.dimensions],
        createdAt = row[EmbeddingsTable.createdAt]
    )

    private fun calculateContentHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}

/**
 * RAG statistics data class
 */
data class RagStatistics(
    val filesCount: Int,
    val chunksCount: Int,
    val embeddingsCount: Int
)
