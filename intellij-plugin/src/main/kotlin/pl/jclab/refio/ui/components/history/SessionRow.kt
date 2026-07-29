package pl.jclab.refio.ui.components.history

import pl.jclab.refio.api.models.Session
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.api.models.TaskStatus

/**
 * Flat view model for one row of the session list.
 *
 * Kept separate from [Session] so the list renderer never has to reach into engine models or
 * re-derive values while painting; everything a row shows is precomputed here.
 */
data class SessionRow(
    val id: String,
    val title: String,
    val kind: TaskMode,
    val status: Status,
    val statusName: String,
    val createdAt: Long,
    val durationMs: Long,
    val generationMs: Long?,
    val tokensIn: Int,
    val tokensOut: Int,
    val costUsd: Double,
    val model: String?,
    val pinned: Boolean
) {

    enum class Status { OK, PARTIAL, FAILED, RUNNING, UNKNOWN }

    /** Second line of the row: time, duration and token counts in one ellipsizable string. */
    val metaText: String
        get() = buildList {
            add(formatTime(createdAt))
            add(formatDuration(durationMs))
            if (tokensIn > 0 || tokensOut > 0) {
                add("${formatCount(tokensIn)}↓ ${formatCount(tokensOut)}↑")
            }
            if (costUsd > 0) add(formatCost(costUsd))
        }.joinToString("  ·  ")

    companion object {

        fun from(session: Session, generationMs: Long?): SessionRow = SessionRow(
            id = session.id,
            title = session.name,
            kind = session.mode,
            status = statusOf(session.status),
            statusName = session.status.name,
            createdAt = session.createdAt,
            durationMs = (session.updatedAt - session.createdAt).coerceAtLeast(0L),
            generationMs = generationMs,
            tokensIn = session.tokensIn,
            tokensOut = session.tokensOut,
            costUsd = session.costUsd,
            model = session.model?.takeIf { it.isNotBlank() },
            pinned = session.pinned
        )

        /**
         * A session that gave up without delivering (INCOMPLETE) is neither a success nor an
         * error, so it gets its own PARTIAL marker instead of being folded into either.
         */
        fun statusOf(status: TaskStatus): Status = when (status) {
            TaskStatus.SUCCESS -> Status.OK
            TaskStatus.FAILED -> Status.FAILED
            TaskStatus.CANCELED -> Status.FAILED
            TaskStatus.INCOMPLETE -> Status.PARTIAL
            TaskStatus.RUNNING -> Status.RUNNING
            else -> Status.UNKNOWN
        }

        fun formatCount(count: Int): String = when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }

        fun formatDuration(millis: Long): String {
            if (millis <= 0) return "-"
            val totalSeconds = millis / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return when {
                hours > 0 -> "${hours}h ${minutes}m"
                minutes > 0 -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
        }

        fun formatCost(usd: Double): String = String.format("$%.4f", usd)

        fun formatTime(epochMs: Long): String =
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(epochMs))
    }
}
