package pl.jclab.refio.ui.components.chat.bubble

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.project.Project
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.ToolCallStatus
import pl.jclab.refio.core.db.MessageMetrics
import pl.jclab.refio.ui.components.chat.toolcall.ToolCallRow
import pl.jclab.refio.ui.components.chat.toolcall.ToolCallRowView
import javax.swing.JPanel

/**
 * Renders a tool result as a single collapsible row rather than a bubble.
 *
 * This class now only extracts what the row needs (name, target path, status, diff size,
 * duration, snapshot) from the message and its originating tool call; the presentation lives in
 * [ToolCallRow].
 */
internal class ToolBubbleRenderer(
    private val context: Context
) : BaseBubbleRenderer() {

    companion object {
        private val PATH_KEYS = listOf("path", "file", "file_path", "filepath", "target_path", "target_file")
        private val SNAPSHOT_KEYS = listOf("snapshot_id", "snapshotId", "snapshot_id_before_write")
    }

    internal interface Context {
        val project: Project
        val messages: List<Message>
        val bubbleContentContext: BubbleContentContext
        val rowCallbacks: ToolCallRow.Callbacks
    }

    private data class RelatedToolCallInfo(
        val toolName: String,
        val path: String?,
        val success: Boolean?
    )

    fun render(message: Message): JPanel {
        val metadata = parseMetadata(message.metadata)
        val related = resolveRelatedToolCallInfo(message)

        return ToolCallRow(
            project = context.project,
            view = buildView(message, metadata, related),
            callbacks = context.rowCallbacks
        )
    }

    private fun buildView(
        message: Message,
        metadata: Map<*, *>?,
        related: RelatedToolCallInfo?
    ): ToolCallRowView {
        val path = extractPath(metadata, related)
        return ToolCallRowView(
            messageId = message.id,
            name = resolveToolName(message, metadata, related) ?: "tool",
            subtitle = path,
            state = resolveState(message, metadata, related),
            added = message.diffSummary?.additions,
            removed = message.diffSummary?.deletions,
            durationMs = resolveDurationMs(message),
            output = message.content,
            snapshotId = message.diffSummary?.snapshotId ?: extractSnapshotId(metadata),
            filePath = path
        )
    }

    /**
     * A still-running call must not be shown as passed. Explicit status from the originating tool
     * call wins over scanning the output text, which produced false errors whenever a diff or log
     * excerpt happened to contain the word "failed".
     */
    private fun resolveState(
        message: Message,
        metadata: Map<*, *>?,
        related: RelatedToolCallInfo?
    ): ToolCallRowView.State {
        if (message.isToolStreaming || message.toolCallInfo?.status == ToolCallStatus.EXECUTING) {
            return ToolCallRowView.State.RUNNING
        }

        resolveExplicitSuccess(metadata, related)?.let {
            return if (it) ToolCallRowView.State.OK else ToolCallRowView.State.FAILED
        }

        val lower = message.content.lowercase()
        val looksLikeError = lower.startsWith("error") ||
            lower.contains(" failed") ||
            lower.contains("error:")
        return if (looksLikeError) ToolCallRowView.State.FAILED else ToolCallRowView.State.OK
    }

    private fun resolveDurationMs(message: Message): Long? {
        MessageMetrics.fromJson(message.metadata)?.toolExecutionTimeMs
            ?.takeIf { it > 0 }
            ?.let { return it.toLong() }
        return message.duration?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
    }

    private fun resolveToolName(
        message: Message,
        metadata: Map<*, *>?,
        relatedToolCallInfo: RelatedToolCallInfo?
    ): String? {
        val metadataToolName = metadata?.get("tool_name")?.toString()
        return if (message.role == "tool" && message.toolCallId != null) {
            relatedToolCallInfo?.toolName ?: message.toolCallInfo?.toolName
        } else {
            message.toolCallInfo?.toolName
        } ?: metadataToolName
    }

    private fun extractPath(metadata: Map<*, *>?, relatedToolCallInfo: RelatedToolCallInfo?): String? {
        return extractPathFromMap(metadata) ?: relatedToolCallInfo?.path
    }

    private fun extractPathFromMap(metadata: Map<*, *>?): String? {
        return PATH_KEYS.firstNotNullOfOrNull { key ->
            metadata?.get(key)?.toString()?.takeIf { it.isNotBlank() }
        }
    }

    private fun extractSnapshotId(metadata: Map<*, *>?): String? {
        return SNAPSHOT_KEYS.firstNotNullOfOrNull { key ->
            metadata?.get(key)?.toString()?.takeIf { it.isNotBlank() }
        }
    }

    private fun resolveRelatedToolCallInfo(message: Message): RelatedToolCallInfo? {
        if (message.role != "tool" || message.toolCallId == null) return null

        return context.messages
            .asSequence()
            .mapNotNull { msg ->
                val info = msg.toolCallInfo ?: return@mapNotNull null
                if (info.toolCallId != message.toolCallId) return@mapNotNull null

                RelatedToolCallInfo(
                    toolName = info.toolName,
                    path = PATH_KEYS.firstNotNullOfOrNull { key ->
                        info.parameters[key]?.takeIf { it.isNotBlank() }
                    },
                    success = info.result?.success ?: when (info.status) {
                        ToolCallStatus.COMPLETED -> true
                        ToolCallStatus.FAILED -> false
                        ToolCallStatus.EXECUTING -> null
                    }
                )
            }
            .firstOrNull()
    }

    private fun resolveExplicitSuccess(
        metadata: Map<*, *>?,
        relatedToolCallInfo: RelatedToolCallInfo?
    ): Boolean? {
        relatedToolCallInfo?.success?.let { return it }
        return when (val raw = metadata?.get("success") ?: metadata?.get("result_success")) {
            is Boolean -> raw
            is String -> raw.toBooleanStrictOrNull()
            else -> null
        }
    }

    private fun parseMetadata(metadata: String?): Map<*, *>? {
        if (metadata.isNullOrBlank()) return null
        return try {
            Gson().fromJson(metadata, TypeToken.get(Map::class.java).type) as? Map<*, *>
        } catch (_: Exception) {
            null
        }
    }
}
