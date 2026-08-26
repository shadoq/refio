package pl.jclab.refio.core.services

import pl.jclab.refio.core.db.repositories.SnapshotGroupRepository
import pl.jclab.refio.core.db.repositories.SnapshotRepository
import pl.jclab.refio.core.db.Snapshot
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.tools.PathSandbox
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
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

            val bytes = fullPath.readBytes()
            Captured(relPath, decodeLossless(bytes), sha256Hash(bytes))
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
     * Describe what restoring [snapshotId] would do to the working tree, so the caller can ask the
     * user before overwriting anything.
     *
     * Note on what this can and cannot tell you: a snapshot holds the state *before* an edit, so a
     * file the agent has just edited legitimately reports [RestoreFileState.DIFFERS_FROM_SNAPSHOT].
     * The post-edit hash is not stored anywhere, so this cannot distinguish the agent's own edit
     * from a later manual edit by the user.
     *
     * @param filePaths Optional list of specific files to inspect (null = all files in the group)
     */
    fun planRestore(
        snapshotId: String,
        filePaths: List<String>? = null
    ): RestorePlan {
        val snapshots = snapshotRepository.findByGroupId(snapshotId)
            .filter { filePaths == null || it.filePath in filePaths }

        val files = snapshots.map { snapshot ->
            val fullPath = projectRoot.resolve(snapshot.filePath).normalize()
            val state = when {
                !fullPath.exists() -> RestoreFileState.MISSING_ON_DISK
                sha256Hash(fullPath.readBytes()) == snapshot.contentHash -> RestoreFileState.MATCHES_SNAPSHOT
                else -> RestoreFileState.DIFFERS_FROM_SNAPSHOT
            }
            RestoreFileInfo(path = snapshot.filePath, state = state)
        }

        return RestorePlan(snapshotId = snapshotId, files = files)
    }

    /**
     * Restore files from snapshot.
     *
     * The current on-disk content is snapshotted first (see [RestoreResult.backupSnapshotId]) so a
     * restore can itself be undone; without that, restoring silently discards whatever the file
     * held. Writes go through [PathSandbox], so a path that escapes the project root is rejected
     * rather than written.
     *
     * @param snapshotId Snapshot ID
     * @param filePaths Optional list of specific files to restore (null = all)
     * @return Result with restored files and any errors
     */
    fun restoreSnapshot(
        snapshotId: String,
        filePaths: List<String>? = null
    ): RestoreResult {
        val snapshots = snapshotRepository.findByGroupId(snapshotId)
            .filter { filePaths == null || it.filePath in filePaths }

        if (snapshots.isEmpty()) {
            return RestoreResult(restoredFiles = emptyList(), errors = emptyList(), success = true)
        }

        val backupSnapshotId = backupCurrentState(snapshots)

        val restored = mutableListOf<String>()
        val errors = mutableListOf<FileError>()

        for (snapshot in snapshots) {
            val relPath = snapshot.filePath
            try {
                // Sandbox owns path validation (traversal, symlink escape) - a snapshot row is
                // still just data, and the path in it must not be trusted at write time.
                val fullPath = sandbox.resolve(relPath)

                val content = snapshotRepository.decompressContent(snapshot)
                fullPath.parent?.createDirectories()
                Files.write(fullPath, encodeForRestore(content, snapshot.contentHash))
                restored.add(relPath)

            } catch (e: SecurityException) {
                errors.add(FileError(relPath, "Path rejected by sandbox: ${e.message}"))
                logger.warn { "Snapshot restore blocked path outside project root: $relPath" }
            } catch (e: Exception) {
                errors.add(FileError(relPath, e.message ?: "Unknown error"))
                logger.error(e) { "Failed to restore file $relPath" }
            }
        }

        return RestoreResult(
            restoredFiles = restored,
            errors = errors,
            success = errors.isEmpty(),
            backupSnapshotId = backupSnapshotId
        )
    }

    /**
     * Snapshot the files that are about to be overwritten, so the restore is reversible.
     * Returns null when none of them currently exists on disk (nothing to lose).
     */
    private fun backupCurrentState(snapshots: List<Snapshot>): String? {
        return try {
            createSnapshot(
                taskId = snapshots.first().taskId,
                subtaskId = null,
                filePaths = snapshots.map { it.filePath }
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to back up current state before restore" }
            null
        }
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

    /**
     * Sandbox for restore writes. Built lazily because [projectRoot] must exist when it is
     * constructed, which is guaranteed at restore time but not necessarily at service construction.
     */
    private val sandbox: PathSandbox by lazy { PathSandbox(projectRoot) }

    /**
     * Decode file bytes without ever losing one.
     *
     * Kotlin's readText does not throw on malformed UTF-8 - it substitutes U+FFFD - so a
     * decode-and-fall-back-on-exception pattern never fires and silently corrupts every non-UTF-8
     * file. Decoding is therefore strict here, and anything that is not valid UTF-8 falls back to
     * ISO-8859-1, which maps each byte to exactly one char and round-trips back to the same bytes.
     */
    private fun decodeLossless(bytes: ByteArray): String {
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, StandardCharsets.ISO_8859_1)
        }
    }

    /**
     * Re-encode snapshot content back to the bytes it was captured from, picking the charset whose
     * output matches the stored hash. UTF-8 is tried first, so snapshots taken before content
     * hashes covered raw bytes (they hashed the UTF-8 form of the decoded string) still match.
     */
    private fun encodeForRestore(content: String, expectedHash: String): ByteArray {
        val utf8 = content.toByteArray(StandardCharsets.UTF_8)
        if (sha256Hash(utf8) == expectedHash) {
            return utf8
        }
        val latin1 = content.toByteArray(StandardCharsets.ISO_8859_1)
        if (sha256Hash(latin1) == expectedHash) {
            return latin1
        }
        logger.warn { "Snapshot content matches no known encoding for its hash; restoring as UTF-8" }
        return utf8
    }

    private fun sha256Hash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    // ========== Data Classes ==========

    data class RestoreResult(
        val restoredFiles: List<String>,
        val errors: List<FileError>,
        val success: Boolean,
        /** Group id of the pre-restore snapshot, or null when no target file existed on disk. */
        val backupSnapshotId: String? = null
    )

    /** State of a snapshotted file in the working tree, as reported by [planRestore]. */
    enum class RestoreFileState {
        /** On-disk content is already byte-identical to the snapshot - restoring changes nothing. */
        MATCHES_SNAPSHOT,
        /** On-disk content differs; restoring overwrites it. */
        DIFFERS_FROM_SNAPSHOT,
        /** File is gone; restoring recreates it. */
        MISSING_ON_DISK
    }

    data class RestoreFileInfo(
        val path: String,
        val state: RestoreFileState
    )

    data class RestorePlan(
        val snapshotId: String,
        val files: List<RestoreFileInfo>
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
