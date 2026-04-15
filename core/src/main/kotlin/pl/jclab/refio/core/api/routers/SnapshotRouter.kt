package pl.jclab.refio.core.api.routers

import pl.jclab.refio.core.api.Router
import pl.jclab.refio.core.api.SnapshotResponse
import pl.jclab.refio.core.db.repositories.SnapshotRepository
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.SnapshotService

private val logger = dualLogger("SnapshotRouter")

/**
 * Router for file snapshot operations.
 * Snapshots are taken before destructive file edits in AGENT mode for rollback.
 *
 * @property snapshotService Present only when CoreApiRouter has a projectRoot.
 *                           When null, snapshot operations throw IllegalStateException.
 */
class SnapshotRouter(
    private val snapshotService: SnapshotService?,
    private val snapshotRepository: SnapshotRepository
) : Router {

    override suspend fun initialize() {
        logger.info { "[SnapshotRouter] Initialized (snapshotService=${snapshotService != null})" }
    }

    override suspend fun shutdown() {
        logger.info { "[SnapshotRouter] Shutting down" }
    }

    suspend fun getSnapshot(snapshotId: String): SnapshotResponse {
        val service = snapshotService
            ?: throw IllegalStateException("Snapshot operations require project context")
        try {
            val files = service.getSnapshot(snapshotId)
            return SnapshotResponse(snapshotId = snapshotId, files = files)
        } catch (e: Exception) {
            logger.error(e) { "Failed to get snapshot: $snapshotId" }
            throw e
        }
    }

    suspend fun getSnapshotFileContent(snapshotId: String, filePath: String): String? {
        val service = snapshotService
            ?: throw IllegalStateException("Snapshot operations require project context")
        return try {
            service.getSnapshot(snapshotId)[filePath]
        } catch (e: Exception) {
            logger.error(e) { "Failed to get snapshot file content: $snapshotId/$filePath" }
            null
        }
    }

    fun deleteSnapshotsByTaskId(taskId: String): Int {
        return snapshotRepository.deleteByTaskId(taskId)
    }
}
