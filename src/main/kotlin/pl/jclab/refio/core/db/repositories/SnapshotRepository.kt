package pl.jclab.refio.core.db.repositories

import pl.jclab.refio.core.db.*
import pl.jclab.refio.services.logging.dualLogger
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

private val logger = dualLogger("SnapshotRepository")

/**
 * Repository for Snapshot database operations
 * Manages file content snapshots with zlib compression for rollback capability
 */
class SnapshotRepository {

    /**
     * Create a new snapshot with compression
     */
    fun create(
        taskId: String,
        subtaskId: String? = null,
        filePath: String,
        content: String,
        contentHash: String
    ): Snapshot {
        return transaction {
            val originalSize = content.toByteArray(Charsets.UTF_8).size.toLong()
            val compressed = compress(content)
            val compressionRatio = originalSize.toDouble() / compressed.size

            val snapshotId = SnapshotsTable.insert {
                it[SnapshotsTable.taskId] = taskId
                it[SnapshotsTable.subtaskId] = subtaskId
                it[SnapshotsTable.filePath] = filePath
                it[SnapshotsTable.contentHash] = contentHash
                it[contentCompressed] = ExposedBlob(compressed)
                it[SnapshotsTable.originalSize] = originalSize
                it[SnapshotsTable.compressionRatio] = compressionRatio
            } get SnapshotsTable.id

            logger.info {
                "Created snapshot: id=$snapshotId, file=$filePath, " +
                "originalSize=$originalSize, compressedSize=${compressed.size}, ratio=$compressionRatio"
            }

            findById(snapshotId) ?: throw IllegalStateException("Failed to retrieve created snapshot")
        }
    }

    /**
     * Find snapshot by ID
     */
    fun findById(id: String): Snapshot? {
        return transaction {
            SnapshotsTable.selectAll()
                .where { SnapshotsTable.id eq id }
                .map { rowToSnapshot(it) }
                .singleOrNull()
        }
    }

    /**
     * Find all snapshots for a task
     */
    fun findByTaskId(taskId: String): List<Snapshot> {
        return transaction {
            SnapshotsTable.selectAll()
                .where { SnapshotsTable.taskId eq taskId }
                .orderBy(SnapshotsTable.createdAt to SortOrder.DESC)
                .map { rowToSnapshot(it) }
        }
    }

    /**
     * Find snapshots for a specific subtask
     */
    fun findBySubtaskId(subtaskId: String): List<Snapshot> {
        return transaction {
            SnapshotsTable.selectAll()
                .where { SnapshotsTable.subtaskId eq subtaskId }
                .orderBy(SnapshotsTable.createdAt to SortOrder.DESC)
                .map { rowToSnapshot(it) }
        }
    }

    /**
     * Find snapshots by file path
     */
    fun findByFilePath(taskId: String, filePath: String): List<Snapshot> {
        return transaction {
            SnapshotsTable.selectAll()
                .where { (SnapshotsTable.taskId eq taskId) and (SnapshotsTable.filePath eq filePath) }
                .orderBy(SnapshotsTable.createdAt to SortOrder.DESC)
                .map { rowToSnapshot(it) }
        }
    }

    /**
     * Find snapshot by file path and content hash
     */
    fun findByFilePathAndHash(taskId: String, filePath: String, contentHash: String): Snapshot? {
        return transaction {
            SnapshotsTable.selectAll()
                .where {
                    (SnapshotsTable.taskId eq taskId) and
                    (SnapshotsTable.filePath eq filePath) and
                    (SnapshotsTable.contentHash eq contentHash)
                }
                .orderBy(SnapshotsTable.createdAt to SortOrder.DESC)
                .limit(1)
                .map { rowToSnapshot(it) }
                .singleOrNull()
        }
    }

    /**
     * Decompress snapshot content
     */
    fun decompressContent(snapshot: Snapshot): String {
        return decompress(snapshot.contentCompressed)
    }

    /**
     * Delete snapshot by ID
     */
    fun delete(id: String): Boolean {
        return transaction {
            val deleted = SnapshotsTable.deleteWhere { SnapshotsTable.id eq id }
            deleted > 0
        }
    }

    /**
     * Delete all snapshots for a task
     */
    fun deleteByTaskId(taskId: String): Int {
        return transaction {
            val deleted = SnapshotsTable.deleteWhere { SnapshotsTable.taskId eq taskId }
            logger.info { "Deleted $deleted snapshots for task: taskId=$taskId" }
            deleted
        }
    }

    /**
     * Count snapshots for a task
     */
    fun countByTaskId(taskId: String): Long {
        return transaction {
            SnapshotsTable.selectAll()
                .where { SnapshotsTable.taskId eq taskId }
                .count()
        }
    }

    /**
     * Compress string content using zlib
     */
    private fun compress(content: String): ByteArray {
        val input = content.toByteArray(Charsets.UTF_8)
        val deflater = Deflater()
        deflater.setInput(input)
        deflater.finish()

        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            outputStream.write(buffer, 0, count)
        }
        outputStream.close()
        deflater.end()

        return outputStream.toByteArray()
    }

    /**
     * Decompress zlib content to string
     */
    private fun decompress(compressed: ByteArray): String {
        val inflater = Inflater()
        inflater.setInput(compressed)

        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            outputStream.write(buffer, 0, count)
        }
        outputStream.close()
        inflater.end()

        return outputStream.toByteArray().toString(Charsets.UTF_8)
    }

    /**
     * Map database row to Snapshot data class
     */
    private fun rowToSnapshot(row: ResultRow): Snapshot {
        return Snapshot(
            id = row[SnapshotsTable.id],
            taskId = row[SnapshotsTable.taskId],
            subtaskId = row[SnapshotsTable.subtaskId],
            filePath = row[SnapshotsTable.filePath],
            contentHash = row[SnapshotsTable.contentHash],
            contentCompressed = row[SnapshotsTable.contentCompressed].bytes,
            originalSize = row[SnapshotsTable.originalSize],
            compressionRatio = row[SnapshotsTable.compressionRatio],
            createdAt = row[SnapshotsTable.createdAt]
        )
    }
}
