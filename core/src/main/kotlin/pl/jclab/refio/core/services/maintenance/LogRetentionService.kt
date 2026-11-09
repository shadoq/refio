package pl.jclab.refio.core.services.maintenance

import pl.jclab.refio.core.agents.events.AgentEventRepository
import pl.jclab.refio.core.db.repositories.ApiLogRepository
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Files
import java.nio.file.Path

private val logger = dualLogger("LogRetentionService")

/** What one sweep deleted. */
data class RetentionReport(
    val apiLogsDeleted: Int,
    val agentEventsDeleted: Int,
    val logFilesDeleted: Int,
)

/**
 * Deletes API call logs, agent events and rolled CLI log files older than the retention period, so
 * hours of agent work do not grow the database and the log folder without end.
 *
 * Each part is swept on its own: a locked database does not keep old log files, and the other way
 * round. The file the CLI is writing to is never deleted, however old its last line.
 */
class LogRetentionService(
    private val apiLogRepository: ApiLogRepository,
    private val agentEventRepository: AgentEventRepository,
    private val logDir: Path,
    private val retentionDays: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun sweep(): RetentionReport {
        val days = retentionDays()
        if (days <= 0) {
            return RetentionReport(0, 0, 0)
        }
        val cutoffMs = clock() - days * DAY_MS
        val report = RetentionReport(
            apiLogsDeleted = part("API logs") { apiLogRepository.deleteOlderThan(cutoffMs, BATCH_SIZE) },
            agentEventsDeleted = part("agent events") { agentEventRepository.deleteOlderThan(cutoffMs, BATCH_SIZE) },
            logFilesDeleted = part("log files") { deleteRolledLogs(cutoffMs) },
        )
        if (report != RetentionReport(0, 0, 0)) {
            logger.info { "[RETENTION] Older than $days days: deleted $report" }
        }
        return report
    }

    private fun part(what: String, sweep: () -> Int): Int =
        runCatching(sweep).getOrElse { e ->
            logger.warn(e) { "[RETENTION] Could not delete old $what: ${e.message}" }
            0
        }

    private fun deleteRolledLogs(cutoffMs: Long): Int {
        if (!Files.isDirectory(logDir)) {
            return 0
        }
        return Files.newDirectoryStream(logDir, ROLLED_LOG_GLOB).use { files ->
            files.count { file ->
                file.fileName.toString() != ACTIVE_LOG &&
                    Files.isRegularFile(file) &&
                    Files.getLastModifiedTime(file).toMillis() < cutoffMs &&
                    Files.deleteIfExists(file)
            }
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val BATCH_SIZE = 500
        const val ACTIVE_LOG = "refio-cli.log"

        /** Files the CLI's rolling log policy leaves behind, e.g. refio-cli.2026-11-09.0.log. */
        const val ROLLED_LOG_GLOB = "refio-cli.*.log"
    }
}
