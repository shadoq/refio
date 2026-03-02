package pl.jclab.refio.core.db.repositories

import pl.jclab.refio.core.db.*
import pl.jclab.refio.services.logging.dualLogger
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = dualLogger("RagRepositories")

/**
 * Repository for IndexFile database operations
 * Tracks files indexed for RAG
 */
class IndexFileRepository {

    /**
     * Create a new index file entry
     */
    fun create(
        projectRoot: String,
        filePath: String,
        fileHash: String,
        checksum: String? = null,
        fileSize: Long,
        mimeType: String?,
        lastModified: Long,
        metadata: String? = null
    ): IndexFile {
        return transaction {
            val fileId = IndexFilesTable.insert {
                it[IndexFilesTable.projectRoot] = projectRoot
                it[IndexFilesTable.filePath] = filePath
                it[IndexFilesTable.fileHash] = fileHash
                it[IndexFilesTable.fileSize] = fileSize
                it[IndexFilesTable.checksum] = checksum
                it[IndexFilesTable.mimeType] = mimeType
                it[IndexFilesTable.lastModified] = lastModified
                it[IndexFilesTable.metadata] = metadata
            } get IndexFilesTable.id

            logger.info { "Indexed file: id=$fileId, path=$filePath, hash=$fileHash" }

            findById(fileId.value) ?: throw IllegalStateException("Failed to retrieve created index file")
        }
    }

    /**
     * Find index file by ID
     */
    fun findById(id: Int): IndexFile? {
        return transaction {
            IndexFilesTable.selectAll()
                .where { IndexFilesTable.id eq id }
                .map { rowToIndexFile(it) }
                .singleOrNull()
        }
    }

    /**
     * Find all indexed files for a project
     */
    fun findByProjectRoot(projectRoot: String): List<IndexFile> {
        return transaction {
            IndexFilesTable.selectAll()
                .where { IndexFilesTable.projectRoot eq projectRoot }
                .orderBy(IndexFilesTable.indexedAt to SortOrder.DESC)
                .map { rowToIndexFile(it) }
        }
    }

    /**
     * Find index file by file hash
     */
    fun findByHash(projectRoot: String, fileHash: String): IndexFile? {
        return transaction {
            IndexFilesTable.selectAll()
                .where { (IndexFilesTable.projectRoot eq projectRoot) and (IndexFilesTable.fileHash eq fileHash) }
                .map { rowToIndexFile(it) }
                .singleOrNull()
        }
    }

    /**
     * Find index file by path
     */
    fun findByPath(projectRoot: String, filePath: String): IndexFile? {
        return transaction {
            IndexFilesTable.selectAll()
                .where { (IndexFilesTable.projectRoot eq projectRoot) and (IndexFilesTable.filePath eq filePath) }
                .map { rowToIndexFile(it) }
                .singleOrNull()
        }
    }

    /**
     * Delete index file by ID (CASCADE will delete related chunks and embeddings)
     */
    fun delete(id: Int): Boolean {
        return transaction {
            val deleted = IndexFilesTable.deleteWhere { IndexFilesTable.id eq id }
            deleted > 0
        }
    }

    /**
     * Delete all index files for a project
     */
    fun deleteByProjectRoot(projectRoot: String): Int {
        return transaction {
            val deleted = IndexFilesTable.deleteWhere { IndexFilesTable.projectRoot eq projectRoot }
            logger.info { "Deleted $deleted index files for project: projectRoot=$projectRoot" }
            deleted
        }
    }

    /**
     * Count indexed files for a project
     */
    fun countByProjectRoot(projectRoot: String): Long {
        return transaction {
            IndexFilesTable.selectAll()
                .where { IndexFilesTable.projectRoot eq projectRoot }
                .count()
        }
    }

    /**
     * Map database row to IndexFile data class
     */
    private fun rowToIndexFile(row: ResultRow): IndexFile {
        return IndexFile(
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
    }
}

/**
 * Repository for IndexChunk database operations
 * Manages content chunks for RAG with FTS5 compatibility
 */
class IndexChunkRepository {

    /**
     * Create a new index chunk
     */
    fun create(
        fileId: Int,
        chunkIndex: Int,
        content: String,
        contentHash: String,
        startLine: Int? = null,
        endLine: Int? = null,
        startChar: Int? = null,
        endChar: Int? = null,
        metadata: String? = null
    ): IndexChunk {
        return transaction {
            val chunkId = IndexChunksTable.insert {
                it[IndexChunksTable.fileId] = fileId
                it[IndexChunksTable.chunkIndex] = chunkIndex
                it[IndexChunksTable.content] = content
                it[IndexChunksTable.contentHash] = contentHash
                it[IndexChunksTable.startLine] = startLine
                it[IndexChunksTable.endLine] = endLine
                it[IndexChunksTable.startChar] = startChar
                it[IndexChunksTable.endChar] = endChar
                it[IndexChunksTable.metadata] = metadata
            } get IndexChunksTable.id

            logger.info { "Created index chunk: id=$chunkId, fileId=$fileId, index=$chunkIndex" }

            findById(chunkId.value) ?: throw IllegalStateException("Failed to retrieve created chunk")
        }
    }

    /**
     * Find chunk by ID
     */
    fun findById(id: Int): IndexChunk? {
        return transaction {
            IndexChunksTable.selectAll()
                .where { IndexChunksTable.id eq id }
                .map { rowToIndexChunk(it) }
                .singleOrNull()
        }
    }

    /**
     * Find all chunks for a file
     */
    fun findByFileId(fileId: Int): List<IndexChunk> {
        return transaction {
            IndexChunksTable.selectAll()
                .where { IndexChunksTable.fileId eq fileId }
                .orderBy(IndexChunksTable.chunkIndex to SortOrder.ASC)
                .map { rowToIndexChunk(it) }
        }
    }

    /**
     * Find all chunks for a project
     */
    fun findByProjectRoot(projectRoot: String): List<IndexChunk> {
        return transaction {
            IndexChunksTable
                .innerJoin(IndexFilesTable, { fileId }, { IndexFilesTable.id })
                .selectAll()
                .where { IndexFilesTable.projectRoot eq projectRoot }
                .orderBy(IndexChunksTable.createdAt to SortOrder.DESC)
                .map { rowToIndexChunk(it) }
        }
    }

    /**
     * Search chunks by content (simple LIKE query)
     */
    fun search(projectRoot: String, query: String, limit: Int = 10): List<IndexChunk> {
        return transaction {
            IndexChunksTable
                .innerJoin(IndexFilesTable, { fileId }, { IndexFilesTable.id })
                .selectAll()
                .where { (IndexFilesTable.projectRoot eq projectRoot) and (IndexChunksTable.content like "%$query%") }
                .limit(limit)
                .map { rowToIndexChunk(it) }
        }
    }

    /**
     * Delete chunk by ID
     */
    fun delete(id: Int): Boolean {
        return transaction {
            val deleted = IndexChunksTable.deleteWhere { IndexChunksTable.id eq id }
            deleted > 0
        }
    }

    /**
     * Delete all chunks for a file
     */
    fun deleteByFileId(fileId: Int): Int {
        return transaction {
            val deleted = IndexChunksTable.deleteWhere { IndexChunksTable.fileId eq fileId }
            deleted
        }
    }

    /**
     * Delete all chunks for a project
     */
    fun deleteByProjectRoot(projectRoot: String): Int {
        return transaction {
            val fileIds = IndexFilesTable
                .selectAll()
                .where { IndexFilesTable.projectRoot eq projectRoot }
                .map { it[IndexFilesTable.id].value }

            val deleted = if (fileIds.isNotEmpty()) {
                IndexChunksTable.deleteWhere { fileId inList fileIds }
            } else 0

            logger.info { "Deleted $deleted chunks for project: projectRoot=$projectRoot" }
            deleted
        }
    }

    /**
     * Count chunks for a file
     */
    fun countByFileId(fileId: Int): Long {
        return transaction {
            IndexChunksTable.selectAll()
                .where { IndexChunksTable.fileId eq fileId }
                .count()
        }
    }

    /**
     * Map database row to IndexChunk data class
     */
    private fun rowToIndexChunk(row: ResultRow): IndexChunk {
        return IndexChunk(
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
    }
}

/**
 * Repository for Embedding database operations
 * Manages vector storage for semantic search
 */
class EmbeddingRepository {

    /**
     * Create a new embedding
     */
    fun create(
        chunkId: Int,
        model: String,
        vector: ByteArray,
        dimensions: Int
    ): Embedding {
        return transaction {
            val embeddingId = EmbeddingsTable.insert {
                it[EmbeddingsTable.chunkId] = chunkId
                it[EmbeddingsTable.model] = model
                it[EmbeddingsTable.vector] = ExposedBlob(vector)
                it[EmbeddingsTable.dimensions] = dimensions
            } get EmbeddingsTable.id

            logger.info { "Created embedding: id=$embeddingId, chunkId=$chunkId, model=$model, dims=$dimensions" }

            findById(embeddingId.value) ?: throw IllegalStateException("Failed to retrieve created embedding")
        }
    }

    /**
     * Find embedding by ID
     */
    fun findById(id: Int): Embedding? {
        return transaction {
            EmbeddingsTable.selectAll()
                .where { EmbeddingsTable.id eq id }
                .map { rowToEmbedding(it) }
                .singleOrNull()
        }
    }

    /**
     * Find embedding for a chunk
     */
    fun findByChunkId(chunkId: Int, model: String): Embedding? {
        return transaction {
            EmbeddingsTable.selectAll()
                .where { (EmbeddingsTable.chunkId eq chunkId) and (EmbeddingsTable.model eq model) }
                .map { rowToEmbedding(it) }
                .singleOrNull()
        }
    }

    /**
     * Find all embeddings for a project
     */
    fun findByProjectRoot(projectRoot: String, model: String? = null): List<Embedding> {
        return transaction {
            val query = EmbeddingsTable
                .innerJoin(IndexChunksTable, { chunkId }, { IndexChunksTable.id })
                .innerJoin(IndexFilesTable, { IndexChunksTable.fileId }, { IndexFilesTable.id })
                .selectAll()
                .where { IndexFilesTable.projectRoot eq projectRoot }

            val filtered = if (model != null) {
                query.andWhere { EmbeddingsTable.model eq model }
            } else query

            filtered.map { rowToEmbedding(it) }
        }
    }

    /**
     * Delete embedding by ID
     */
    fun delete(id: Int): Boolean {
        return transaction {
            val deleted = EmbeddingsTable.deleteWhere { EmbeddingsTable.id eq id }
            deleted > 0
        }
    }

    /**
     * Delete embeddings for a chunk
     */
    fun deleteByChunkId(chunkId: Int): Int {
        return transaction {
            val deleted = EmbeddingsTable.deleteWhere { EmbeddingsTable.chunkId eq chunkId }
            deleted
        }
    }

    /**
     * Delete all embeddings for a project
     */
    fun deleteByProjectRoot(projectRoot: String): Int {
        return transaction {
            val chunkIds = IndexChunksTable
                .innerJoin(IndexFilesTable, { fileId }, { IndexFilesTable.id })
                .selectAll()
                .where { IndexFilesTable.projectRoot eq projectRoot }
                .map { it[IndexChunksTable.id].value }

            val deleted = if (chunkIds.isNotEmpty()) {
                EmbeddingsTable.deleteWhere { chunkId inList chunkIds }
            } else 0

            logger.info { "Deleted $deleted embeddings for project: projectRoot=$projectRoot" }
            deleted
        }
    }

    /**
     * Count embeddings for a project
     */
    fun countByProjectRoot(projectRoot: String): Long {
        return transaction {
            EmbeddingsTable
                .innerJoin(IndexChunksTable, { chunkId }, { IndexChunksTable.id })
                .innerJoin(IndexFilesTable, { IndexChunksTable.fileId }, { IndexFilesTable.id })
                .selectAll()
                .where { IndexFilesTable.projectRoot eq projectRoot }
                .count()
        }
    }

    /**
     * Map database row to Embedding data class
     */
    private fun rowToEmbedding(row: ResultRow): Embedding {
        return Embedding(
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
 * Repository for tracking indexing progress per project.
 */
class IndexingProgressRepository {

    fun markStarted(
        projectRoot: String,
        totalFiles: Int,
        message: String? = null
    ): IndexingProgressEntry = transaction {
        val now = System.currentTimeMillis()
        val existing = findRow(projectRoot)

        if (existing == null) {
            IndexingProgressTable.insert {
                it[IndexingProgressTable.projectRoot] = projectRoot
                it[status] = IndexingProgressStatus.IN_PROGRESS.name
                it[IndexingProgressTable.totalFiles] = totalFiles
                it[indexedFiles] = 0
                it[startedAt] = now
                it[completedAt] = null
                it[lastIndexedFile] = null
                it[IndexingProgressTable.message] = message
            }
        } else {
            IndexingProgressTable.update({ IndexingProgressTable.projectRoot eq projectRoot }) {
                it[status] = IndexingProgressStatus.IN_PROGRESS.name
                it[IndexingProgressTable.totalFiles] = totalFiles
                it[indexedFiles] = 0
                it[startedAt] = now
                it[completedAt] = null
                it[lastIndexedFile] = null
                it[IndexingProgressTable.message] = message
            }
        }

        get(projectRoot)!!
    }

    fun updateProgress(
        projectRoot: String,
        indexedFiles: Int,
        message: String? = null,
        lastIndexedFile: String? = null
    ): IndexingProgressEntry? = transaction {
        if (findRow(projectRoot) == null) {
            return@transaction null
        }
        IndexingProgressTable.update({ IndexingProgressTable.projectRoot eq projectRoot }) {
            it[IndexingProgressTable.indexedFiles] = indexedFiles
            it[IndexingProgressTable.message] = message
            it[IndexingProgressTable.lastIndexedFile] = lastIndexedFile
        }
        get(projectRoot)
    }

    fun markCompleted(
        projectRoot: String,
        indexedFiles: Int,
        message: String? = null
    ): IndexingProgressEntry? = markTerminalStatus(
        projectRoot = projectRoot,
        indexedFiles = indexedFiles,
        status = IndexingProgressStatus.COMPLETED,
        message = message
    )

    fun markCancelled(
        projectRoot: String,
        indexedFiles: Int,
        message: String? = null
    ): IndexingProgressEntry? = markTerminalStatus(
        projectRoot = projectRoot,
        indexedFiles = indexedFiles,
        status = IndexingProgressStatus.CANCELLED,
        message = message
    )

    fun markFailed(
        projectRoot: String,
        indexedFiles: Int,
        message: String? = null
    ): IndexingProgressEntry? = markTerminalStatus(
        projectRoot = projectRoot,
        indexedFiles = indexedFiles,
        status = IndexingProgressStatus.FAILED,
        message = message
    )

    fun get(projectRoot: String): IndexingProgressEntry? = transaction {
        findRow(projectRoot)?.let { rowToEntry(it) }
    }

    fun clear(projectRoot: String) = transaction {
        IndexingProgressTable.deleteWhere { IndexingProgressTable.projectRoot eq projectRoot }
    }

    private fun markTerminalStatus(
        projectRoot: String,
        indexedFiles: Int,
        status: IndexingProgressStatus,
        message: String?
    ): IndexingProgressEntry? = transaction {
        val timestamp = System.currentTimeMillis()
        if (findRow(projectRoot) == null) return@transaction null

        IndexingProgressTable.update({ IndexingProgressTable.projectRoot eq projectRoot }) {
            it[IndexingProgressTable.status] = status.name
            it[IndexingProgressTable.indexedFiles] = indexedFiles
            it[completedAt] = timestamp
            it[IndexingProgressTable.message] = message
        }

        get(projectRoot)
    }

    private fun findRow(projectRoot: String): ResultRow? {
        return IndexingProgressTable
            .selectAll().where { IndexingProgressTable.projectRoot eq projectRoot }
            .singleOrNull()
    }

    private fun rowToEntry(row: ResultRow): IndexingProgressEntry {
        val statusValue = row[IndexingProgressTable.status]
        val status = runCatching { IndexingProgressStatus.valueOf(statusValue) }
            .getOrDefault(IndexingProgressStatus.IN_PROGRESS)

        return IndexingProgressEntry(
            projectRoot = row[IndexingProgressTable.projectRoot],
            status = status,
            totalFiles = row[IndexingProgressTable.totalFiles],
            indexedFiles = row[IndexingProgressTable.indexedFiles],
            startedAt = row[IndexingProgressTable.startedAt],
            completedAt = row[IndexingProgressTable.completedAt],
            lastIndexedFile = row[IndexingProgressTable.lastIndexedFile],
            message = row[IndexingProgressTable.message]
        )
    }
}
