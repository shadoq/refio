package pl.jclab.refio.ui.components.chat.toolcall

/**
 * Everything a collapsed tool-call row shows, precomputed.
 *
 * A transcript with twenty tool calls is unreadable when each one takes a card, so a call is
 * reduced to a single line: status, name, target, diff size, duration. The detail (diff or raw
 * output) is only materialised when the user expands the row.
 */
data class ToolCallRowView(
    val messageId: String,
    val name: String,
    val subtitle: String?,
    val state: State,
    val added: Int?,
    val removed: Int?,
    val durationMs: Long?,
    val output: String,
    val snapshotId: String?,
    val filePath: String?
) {

    enum class State { OK, FAILED, RUNNING }

    /** A real IDE diff is only possible when we can reconstruct the "before" side. */
    val canDiff: Boolean
        get() = !snapshotId.isNullOrBlank() && !filePath.isNullOrBlank()

    val diffText: String?
        get() {
            val a = added ?: 0
            val r = removed ?: 0
            return if (added == null && removed == null) null else "+$a −$r"
        }

    val durationText: String?
        get() = durationMs?.takeIf { it > 0 }?.let { formatDuration(it) }

    companion object {

        fun formatDuration(millis: Long): String = when {
            millis < 1000 -> "${millis}ms"
            millis < 60_000 -> String.format("%.1fs", millis / 1000.0)
            else -> "${millis / 60_000}m ${(millis % 60_000) / 1000}s"
        }

        /**
         * Middle-elides a path so it stays on one line. Directory structure at the front and the
         * file name at the end are what identify a file; the middle is the expendable part.
         */
        fun shortenPath(path: String, maxLength: Int = 34): String {
            val normalized = path.replace('\\', '/')
            if (normalized.length <= maxLength) return normalized

            val fileName = normalized.substringAfterLast('/')
            if (fileName.length + 4 >= maxLength) {
                return "…" + fileName.takeLast(maxLength - 1)
            }

            val head = normalized.take(maxLength - fileName.length - 2)
            return "$head…/$fileName"
        }
    }
}
