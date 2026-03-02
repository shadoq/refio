package pl.jclab.refio.core.db

import org.jetbrains.exposed.sql.*
import java.util.UUID

/**
 * Snapshots table definition using Exposed ORM DSL
 * Stores file content snapshots for rollback capability
 */
object SnapshotsTable : Table("snapshots") {
    val id = varchar("id", 36).clientDefault { UUID.randomUUID().toString() }
    val taskId = varchar("task_id", 36).references(TasksTable.id, onDelete = ReferenceOption.CASCADE)
    val subtaskId = varchar("subtask_id", 36).references(SubtasksTable.id, onDelete = ReferenceOption.CASCADE).nullable()

    val filePath = text("file_path")  // Absolute or relative path within sandbox
    val contentHash = varchar("content_hash", 64)  // SHA-256 hash
    val contentCompressed = blob("content_compressed")  // zlib compressed content
    val originalSize = long("original_size")  // Size in bytes before compression
    val compressionRatio = double("compression_ratio").default(1.0)  // originalSize / compressedSize

    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }

    override val primaryKey = PrimaryKey(id)

    init {
        // Index for efficient retrieval of snapshots by task
        index("idx_snapshots_task", false, taskId, createdAt)
        // Index for finding snapshots by file path
        index("idx_snapshots_file_hash", false, filePath, contentHash)
    }
}

/**
 * Snapshot data class for results
 */
data class Snapshot(
    val id: String,
    val taskId: String,
    val subtaskId: String?,
    val filePath: String,
    val contentHash: String,
    val contentCompressed: ByteArray,
    val originalSize: Long,
    val compressionRatio: Double,
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Snapshot

        if (id != other.id) return false
        if (!contentCompressed.contentEquals(other.contentCompressed)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + contentCompressed.contentHashCode()
        return result
    }
}
