package pl.jclab.refio.core.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*

/**
 * RAG (Retrieval-Augmented Generation) tables for knowledge base functionality
 */

/**
 * Content type for RAG entries
 */
enum class RagContentType {
    PROJECT_CODE,      // Project files (sandbox-aware)
    DOCUMENTATION,     // External documentation (URLs)
    USER_NOTES         // User notes (future)
}

/**
 * Index files table - Tracks files indexed for RAG
 * NOTE: Uses project_root instead of task_id because RAG is per-project, not per-task
 */
object IndexFilesTable : IntIdTable("index_files") {
    val projectRoot = text("project_root")  // Absolute path to project root directory

    val filePath = text("file_path")  // Relative or absolute path
    val fileHash = varchar("file_hash", 64)  // SHA-256 hash of file content
    val checksum = varchar("checksum", 64).nullable()  // SHA-256 checksum used for incremental indexing
    val fileSize = long("file_size")  // Size in bytes
    val mimeType = varchar("mime_type", 128).nullable()
    val metadata = text("metadata").nullable()  // JSON with analyzer metadata

    val contentType = enumerationByName<RagContentType>("content_type", 32).default(RagContentType.PROJECT_CODE)
    val sourceUrl = text("source_url").nullable()  // For DOCUMENTATION type

    val indexedAt = long("indexed_at").clientDefault { System.currentTimeMillis() }
    val lastModified = long("last_modified")  // File system modification time

    init {
        // Index for efficient file lookups by project
        index("idx_index_files_project", false, projectRoot, filePath)
        index("idx_index_files_hash", false, fileHash)
        index("idx_index_files_checksum", false, checksum)
        index("idx_index_files_content_type", false, projectRoot, contentType)
    }
}

/**
 * Index chunks table - Content chunks for RAG with FTS5 compatibility
 * Note: Uses INTEGER ROWID for FTS5 virtual table compatibility
 * NOTE: No task_id needed - project context comes from fileId → IndexFilesTable.projectRoot
 */
object IndexChunksTable : IntIdTable("index_chunks") {
    val fileId = integer("file_id").references(IndexFilesTable.id, onDelete = ReferenceOption.CASCADE)

    val chunkIndex = integer("chunk_index")  // Position in file (0-indexed)
    val content = text("content")  // Actual text content
    val contentHash = varchar("content_hash", 64)  // SHA-256 hash of content
    val metadata = text("metadata").nullable()  // JSON payload (class/function info)

    val startLine = integer("start_line").nullable()
    val endLine = integer("end_line").nullable()
    val startChar = integer("start_char").nullable()
    val endChar = integer("end_char").nullable()

    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }

    init {
        // Index for efficient chunk retrieval
        index("idx_index_chunks_file", false, fileId, chunkIndex)
        // Unique constraint on file + chunk index
        uniqueIndex("uk_file_chunk", fileId, chunkIndex)
    }
}

/**
 * Embeddings table - Vector storage for semantic search
 * Stores float32 embeddings as BLOB (little-endian byte order)
 * NOTE: No task_id needed - project context comes from chunkId → fileId → IndexFilesTable.projectRoot
 */
object EmbeddingsTable : IntIdTable("embeddings") {
    val chunkId = integer("chunk_id").references(IndexChunksTable.id, onDelete = ReferenceOption.CASCADE)

    val model = varchar("model", 64)  // Embedding model used (e.g., "text-embedding-ada-002")
    val vector = blob("vector")  // Float32 array as BLOB (little-endian)
    val dimensions = integer("dimensions")  // Vector dimensionality

    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }

    init {
        // Index for efficient embedding lookups
        index("idx_embeddings_chunk", false, chunkId)
        index("idx_embeddings_model", false, model)
        // Unique constraint: one embedding per chunk per model
        uniqueIndex("uk_chunk_model", chunkId, model)
    }
}

/**
 * Index file data class for results
 */
data class IndexFile(
    val id: Int,
    val projectRoot: String,
    val filePath: String,
    val fileHash: String,
    val checksum: String?,
    val fileSize: Long,
    val mimeType: String?,
    val metadata: String?,
    val contentType: RagContentType = RagContentType.PROJECT_CODE,
    val sourceUrl: String? = null,
    val indexedAt: Long,
    val lastModified: Long
)

/**
 * Index chunk data class for results
 */
data class IndexChunk(
    val id: Int,
    val fileId: Int,
    val chunkIndex: Int,
    val content: String,
    val contentHash: String,
    val metadata: String?,
    val startLine: Int?,
    val endLine: Int?,
    val startChar: Int?,
    val endChar: Int?,
    val createdAt: Long
)

/**
 * Embedding data class for results
 */
data class Embedding(
    val id: Int,
    val chunkId: Int,
    val model: String,
    val vector: ByteArray,
    val dimensions: Int,
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Embedding

        if (id != other.id) return false
        if (!vector.contentEquals(other.vector)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + vector.contentHashCode()
        return result
    }
}

/**
 * Indexing progress table - Tracks background indexing status per project
 */
object IndexingProgressTable : Table("indexing_progress") {
    val projectRoot = text("project_root")
    val status = varchar("status", length = 20)  // IN_PROGRESS, COMPLETED, CANCELLED, FAILED
    val totalFiles = integer("total_files").default(0)
    val indexedFiles = integer("indexed_files").default(0)
    val startedAt = long("started_at")
    val completedAt = long("completed_at").nullable()
    val lastIndexedFile = text("last_indexed_file").nullable()
    val message = text("message").nullable()

    override val primaryKey = PrimaryKey(projectRoot)
}

/**
 * Indexing progress status enumeration
 */
enum class IndexingProgressStatus {
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    FAILED
}

/**
 * Indexing progress data class
 */
data class IndexingProgressEntry(
    val projectRoot: String,
    val status: IndexingProgressStatus,
    val totalFiles: Int,
    val indexedFiles: Int,
    val startedAt: Long,
    val completedAt: Long?,
    val lastIndexedFile: String?,
    val message: String?
)

/**
 * Documentation indexing status
 */
enum class DocIndexingStatus {
    PENDING,      // Added but not yet indexed
    INDEXING,     // Currently being indexed
    INDEXED,      // Successfully indexed
    FAILED,       // Indexing failed
    PAUSED        // Indexing paused by user
}

/**
 * Documentation source type
 */
enum class DocSourceType {
    URL,
    FILE
}

/**
 * Documentation sources table - Tracks external documentation URLs
 * NOTE: Uses project_root instead of task_id because documentation is per-project, not per-task
 */
object DocumentationSourcesTable : IntIdTable("documentation_sources") {
    val projectRoot = text("project_root")  // Absolute path to project root directory

    val url = text("url")  // Base URL (e.g., "https://kotlinlang.org/docs/")
    val sourceType = enumerationByName<DocSourceType>("source_type", 16).default(DocSourceType.URL)
    val filePath = text("file_path").nullable()  // Absolute path for local file sources
    val title = text("title").nullable()  // Extracted from page title
    val description = text("description").nullable()

    val status = enumerationByName<DocIndexingStatus>("status", 32).default(DocIndexingStatus.PENDING)
    val errorMessage = text("error_message").nullable()

    val crawlDepth = integer("crawl_depth").default(2)  // Max depth for crawling
    val pagesIndexed = integer("pages_indexed").default(0)
    val totalPages = integer("total_pages").default(0)

    val lastIndexed = long("last_indexed").nullable()
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }

    init {
        index("idx_doc_sources_project_url", false, projectRoot, url)
    }
}

/**
 * Documentation source data class
 */
data class DocumentationSource(
    val id: Int,
    val projectRoot: String,
    val url: String,
    val sourceType: DocSourceType,
    val filePath: String?,
    val title: String?,
    val description: String?,
    val status: DocIndexingStatus,
    val errorMessage: String?,
    val crawlDepth: Int,
    val pagesIndexed: Int,
    val totalPages: Int,
    val lastIndexed: Long?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Documentation statistics data class
 */
data class DocStatistics(
    val totalSources: Int,
    val indexedSources: Int,
    val totalPages: Int,
    val failedSources: Int
)
