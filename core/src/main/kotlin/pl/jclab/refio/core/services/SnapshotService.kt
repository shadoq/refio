package pl.jclab.refio.core.services

import pl.jclab.refio.core.db.repositories.SnapshotRepository
import pl.jclab.refio.core.db.Snapshot
import pl.jclab.refio.core.logging.dualLogger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.io.path.*

/**
 * Service for managing file snapshots with compression.
 * Provides file versioning and rollback capabilities.
 */
class SnapshotService(
    private val snapshotRepository: SnapshotRepository,
    private val projectRoot: Path
) {
    private val logger = dualLogger("SnapshotService")

    /**
     * Create snapshots for specified files.
     *
     * @param taskId Task ID
     * @param subtaskId Subtask ID (used as snapshot identifier)
     * @param filePaths List of relative file paths to snapshot
     * @return Snapshot ID (same as subtask_id)
     */
    fun createSnapshot(
        taskId: String,
        subtaskId: String,
        filePaths: List<String>
    ): String {
        for (relPath in filePaths) {
            val fullPath = projectRoot.resolve(relPath).normalize()

            // Skip if file doesn't exist (for create operations)
            if (!fullPath.exists()) {
                continue
            }

            // Read content
            val content = try {
                fullPath.readText(StandardCharsets.UTF_8)
            } catch (e: Exception) {
                // Binary file - read as bytes and decode as latin-1
                String(fullPath.readBytes(), charset("ISO-8859-1"))
            }

            // Calculate hash
            val contentHash = sha256Hash(content)

            // Create snapshot record (repository handles compression)
            snapshotRepository.create(
                taskId = taskId,
                subtaskId = subtaskId,
                filePath = relPath,
                content = content,
                contentHash = contentHash
            )
        }

        return subtaskId
    }

    /**
     * Get all files in a snapshot.
     *
     * @param snapshotId Snapshot ID (subtask_id)
     * @return Map of file_path to content
     */
    fun getSnapshot(snapshotId: String): Map<String, String> {
        val snapshots = snapshotRepository.findBySubtaskId(snapshotId)

        return snapshots.associate { snapshot ->
            val content = snapshotRepository.decompressContent(snapshot)
            snapshot.filePath to content
        }
    }

    /**
     * Get content of specific file from snapshot.
     *
     * @param snapshotId Snapshot ID
     * @param filePath Relative file path
     * @return File content or null if not found
     */
    fun getFileContent(snapshotId: String, filePath: String): String? {
        val snapshots = snapshotRepository.findBySubtaskId(snapshotId)
        val snapshot = snapshots.find { it.filePath == filePath } ?: return null

        return snapshotRepository.decompressContent(snapshot)
    }

    /**
     * Restore files from snapshot.
     *
     * @param snapshotId Snapshot ID
     * @param filePaths Optional list of specific files to restore (null = all)
     * @return Result with restored files and any errors
     */
    fun restoreSnapshot(
        snapshotId: String,
        filePaths: List<String>? = null
    ): RestoreResult {
        var snapshotData = getSnapshot(snapshotId)

        // Filter to requested files if specified
        if (filePaths != null) {
            snapshotData = snapshotData.filterKeys { it in filePaths }
        }

        val restored = mutableListOf<String>()
        val errors = mutableListOf<FileError>()

        val normalizedRoot = projectRoot.toRealPath()

        for ((relPath, content) in snapshotData) {
            val fullPath = projectRoot.resolve(relPath).normalize()

            try {
                // Validate path stays within project root (defense against path traversal from DB).
                // Resolve via normalizedRoot (which uses toRealPath) to handle platform symlinks
                // (e.g. macOS /var -> /private/var) for both existing and non-existing paths.
                val resolvedPath = normalizedRoot.resolve(relPath).normalize()
                if (!resolvedPath.startsWith(normalizedRoot)) {
                    errors.add(FileError(relPath, "Path traversal blocked: resolves outside project root"))
                    logger.warn { "Snapshot restore blocked path traversal: $relPath -> $resolvedPath" }
                    continue
                }

                // Ensure parent directory exists
                fullPath.parent?.createDirectories()

                // Write content
                fullPath.writeText(content, StandardCharsets.UTF_8)
                restored.add(relPath)

            } catch (e: Exception) {
                errors.add(FileError(relPath, e.message ?: "Unknown error"))
                logger.error(e) { "Failed to restore file $relPath" }
            }
        }

        return RestoreResult(
            restoredFiles = restored,
            errors = errors,
            success = errors.isEmpty()
        )
    }

    /**
     * Get most recent snapshot for a specific file.
     *
     * @param taskId Task ID
     * @param filePath Relative file path
     * @return Snapshot or null if not found
     */
    fun getLatestSnapshotForFile(taskId: String, filePath: String): Snapshot? {
        val snapshots = snapshotRepository.findByFilePath(taskId, filePath)
        return snapshots.firstOrNull()
    }

    /**
     * List all snapshots for a task, grouped by subtask_id.
     *
     * @param taskId Task ID
     * @return List of snapshot info
     */
    fun listSnapshotsForTask(taskId: String): List<SnapshotInfo> {
        val snapshots = snapshotRepository.findByTaskId(taskId)

        // Group by subtask_id
        val grouped = snapshots.groupBy { it.subtaskId }

        return grouped.map { (subtaskId, snapshotList) ->
            SnapshotInfo(
                snapshotId = subtaskId ?: "unknown",
                createdAt = snapshotList.first().createdAt,
                files = snapshotList.map { snapshot ->
                    FileInfo(
                        path = snapshot.filePath,
                        hash = snapshot.contentHash
                    )
                }
            )
        }.sortedByDescending { it.createdAt }
    }

    /**
     * Remove old snapshots, keeping only N most recent.
     *
     * @param taskId Task ID
     * @param keepLatest Number of snapshots to keep
     * @return Number of snapshot records deleted
     */
    fun cleanupOldSnapshots(taskId: String, keepLatest: Int = 100): Int {
        val allSnapshots = listSnapshotsForTask(taskId)

        if (allSnapshots.size <= keepLatest) {
            return 0
        }

        // Get IDs to delete (oldest ones)
        val toDelete = allSnapshots.drop(keepLatest).map { it.snapshotId }

        // Delete snapshots
        var deleted = 0
        for (snapshotId in toDelete) {
            // Delete all snapshots with this subtask_id
            val snapshots = snapshotRepository.findBySubtaskId(snapshotId)
            for (snapshot in snapshots) {
                if (snapshotRepository.delete(snapshot.id)) {
                    deleted++
                }
            }
        }

        logger.info { "Cleaned up $deleted snapshot records for task $taskId" }
        return deleted
    }

    // ========== Utility Functions ==========

    private fun sha256Hash(content: String): String {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    // ========== Data Classes ==========

    data class RestoreResult(
        val restoredFiles: List<String>,
        val errors: List<FileError>,
        val success: Boolean
    )

    data class FileError(
        val file: String,
        val error: String
    )

    data class SnapshotInfo(
        val snapshotId: String,
        val createdAt: Long,
        val files: List<FileInfo>
    )

    data class FileInfo(
        val path: String,
        val hash: String
    )
}
