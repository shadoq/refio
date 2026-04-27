package pl.jclab.refio.core.services

import pl.jclab.refio.core.db.repositories.SnapshotGroupRepository
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
    private val snapshotGroupRepository: SnapshotGroupRepository,
    private val projectRoot: Path
) {
    private val logger = dualLogger("SnapshotService")

    /**
     * Create snapshots for specified files. Only files that exist on disk are
     * captured — for create-file operations, nothing is snapshotted.
     *
     * @return group id of the created snapshot group, or null when no file was
     *         actually snapshotted (no existing files among [filePaths]).
     */
    fun createSnapshot(
        taskId: String,
        subtaskId: String?,
        filePaths: List<String>
    ): String? {
        data class Captured(val relPath: String, val content: String, val hash: String)

        val captured = filePaths.mapNotNull { relPath ->
            val fullPath = projectRoot.resolve(relPath).normalize()
            if (!fullPath.exists()) return@mapNotNull null

            val content = try {
                fullPath.readText(StandardCharsets.UTF_8)
            } catch (e: Exception) {
                String(fullPath.readBytes(), charset("ISO-8859-1"))
            }
            Captured(relPath, content, sha256Hash(content))
        }

        if (captured.isEmpty()) {
            logger.info {
                "No files to snapshot (all paths missing): taskId=$taskId, subtaskId=$subtaskId, paths=$filePaths"
            }
            return null
        }

        val group = snapshotGroupRepository.create(taskId = taskId, subtaskId = subtaskId)

        for (file in captured) {
            snapshotRepository.create(
                taskId = taskId,
                groupId = group.id,
                filePath = file.relPath,
                content = file.content,
                contentHash = file.hash
            )
        }

        return group.id
    }

    /**
     * Get all files in a snapshot group.
     *
     * @param snapshotId Snapshot group id
     * @return Map of file_path to content
     */
    fun getSnapshot(snapshotId: String): Map<String, String> {
        val snapshots = snapshotRepository.findByGroupId(snapshotId)

        return snapshots.associate { snapshot ->
            val content = snapshotRepository.decompressContent(snapshot)
            snapshot.filePath to content
        }
    }

    /**
     * Get content of specific file from snapshot group.
     */
    fun getFileContent(snapshotId: String, filePath: String): String? {
        val snapshots = snapshotRepository.findByGroupId(snapshotId)
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
     * List all snapshots for a task, grouped by snapshot group id.
     */
    fun listSnapshotsForTask(taskId: String): List<SnapshotInfo> {
        val snapshots = snapshotRepository.findByTaskId(taskId)

        val grouped = snapshots.groupBy { it.groupId }

        return grouped.map { (groupId, snapshotList) ->
            SnapshotInfo(
                snapshotId = groupId,
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
            val snapshots = snapshotRepository.findByGroupId(snapshotId)
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
