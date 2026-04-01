package pl.jclab.refio.ui.components.chat.bubble

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JPanel

internal class ToolBubbleRenderer(
    private val context: Context
) : BaseBubbleRenderer() {

    companion object {
        private val DIFF_FOCUSED_TOOLS = setOf(
            "advance_code_editing",
            "multi_line_editor",
            "code_editing"
        )
        private val PATH_KEYS = listOf("path", "file", "file_path", "filepath", "target_path", "target_file")
    }

    internal interface Context {
        val messages: List<Message>
        val bubbleContentContext: BubbleContentContext
    }

    private data class RenderStatus(
        val isError: Boolean,
        val icon: String
    )

    private data class RelatedToolCallInfo(
        val toolName: String,
        val path: String?
    )

    private val factory get() = context.bubbleContentContext.componentFactory

    fun render(message: Message): JPanel {
        if (message.content.isBlank()) return createOuterPanel()

        val metadata = parseMetadata(message.metadata)
        val relatedToolCallInfo = resolveRelatedToolCallInfo(message)
        val toolName = resolveToolName(message, metadata, relatedToolCallInfo)
        val status = resolveStatus(message.content)

        val title = buildToolResultTitle(toolName, metadata)
        if (shouldUseCodeBlock(toolName, message.content, status)) {
            return createCodeResultBubble(
                title = title,
                content = message.content,
                toolName = toolName,
                metadata = metadata,
                relatedToolCallInfo = relatedToolCallInfo
            )
        }

        val content = buildRenderableContent(
            content = message.content,
            toolName = toolName,
            metadata = metadata,
            status = status
        )

        return createRegularToolResultBubble(title, content)
    }

    private fun createRegularToolResultBubble(title: String, content: String): JPanel {
        val outerPanel = createOuterPanel()
        val messageBlock = context.bubbleContentContext.createMessageBlock(LCATheme.toolResultBackground).apply {
            layout = GridBagLayout()
        }
        var row = 0

        fun addRow(component: JComponent, topInset: Int = 0) {
            messageBlock.add(
                component,
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = row++
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = Insets(topInset, 0, 0, 0)
                }
            )
        }

        addRow(
            factory.createBubbleHeader(
                icon = "\uD83D\uDD27",
                title = title,
                foregroundColor = LCATheme.toolResultForeground
            )
        )
        addRow(
            factory.createBubbleContentPanel(
                content = content,
                backgroundColor = LCATheme.toolResultBackground,
                foregroundColor = LCATheme.toolResultForeground,
                isUser = false
            )
        )

        return addToOuter(outerPanel, messageBlock)
    }

    private fun createCodeResultBubble(
        title: String,
        content: String,
        toolName: String?,
        metadata: Map<*, *>?,
        relatedToolCallInfo: RelatedToolCallInfo?
    ): JPanel {
        val outerPanel = createOuterPanel()
        val messageBlock = context.bubbleContentContext.createMessageBlock(LCATheme.toolResultBackground).apply {
            layout = GridBagLayout()
        }
        var row = 0

        fun addRow(component: JComponent, topInset: Int = 0) {
            messageBlock.add(
                component,
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = row++
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = Insets(topInset, 0, 0, 0)
                }
            )
        }

        addRow(
            factory.createBubbleHeader(
                icon = "\uD83D\uDD27",
                title = title,
                foregroundColor = LCATheme.toolResultForeground
            )
        )

        val filePath = extractPath(metadata, relatedToolCallInfo)

        addRow(
            createCollapsibleCodePanel(
                content = content,
                context = context.bubbleContentContext,
                language = inferLanguage(toolName, metadata, relatedToolCallInfo),
                filePath = filePath
            ).apply {
                alignmentX = Component.LEFT_ALIGNMENT
            },
            topInset = context.bubbleContentContext.bubbleCompactGap
        )

        return addToOuter(outerPanel, messageBlock)
    }

    private fun buildRenderableContent(
        content: String,
        toolName: String?,
        metadata: Map<*, *>?,
        status: RenderStatus
    ): String {
        val normalized = normalizeText(content)

        if (normalized.isBlank()) {
            return if (status.isError) "${status.icon} Error" else "${status.icon} No output"
        }

        if (isDiffContent(content)) {
            return "${status.icon} Diff\n\n${wrapDiffInCodeBlock(content)}"
        }

        val normalizedTool = toolName?.lowercase()
        if (!status.isError && normalizedTool in DIFF_FOCUSED_TOOLS) {
            val extractedDiff = extractDiffPayload(content)
            if (!extractedDiff.isNullOrBlank()) {
                return "${status.icon} Diff\n\n```diff\n$extractedDiff\n```"
            }
        }

        if (shouldUseCodeBlock(toolName, content, status)) {
            val language = inferLanguage(toolName, metadata)
            return buildString {
                append("${status.icon} Output")
                append("\n\n```")
                append(language)
                append('\n')
                append(content.replace("\r\n", "\n").replace('\r', '\n').trim())
                append("\n```")
            }
        }

        return "${status.icon} $normalized"
    }

    private fun extractDiffPayload(content: String): String? {
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isBlank()) return null

        val fencedDiff = Regex("```diff\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (!fencedDiff.isNullOrBlank()) return fencedDiff

        val markerRegex = Regex("(?is)\\bdiff:\\s*(.+)$")
        val markerMatch = markerRegex.find(normalized)?.groupValues?.getOrNull(1)?.trim()
        if (!markerMatch.isNullOrBlank() && looksLikeDiff(markerMatch)) {
            return markerMatch
        }

        val lines = normalized.lines()
        val start = lines.indexOfFirst {
            it.startsWith("diff --git") || it.startsWith("--- ") || it.startsWith("+++ ")
        }
        if (start >= 0) {
            val tail = lines.drop(start).joinToString("\n").trim()
            if (looksLikeDiff(tail)) return tail
        }

        return if (looksLikeDiff(normalized)) normalized else null
    }

    private fun looksLikeDiff(content: String): Boolean {
        val lines = content.lines()
        val hasHeader = lines.any { it.startsWith("diff --git") } ||
            (lines.any { it.startsWith("--- ") } && lines.any { it.startsWith("+++ ") })
        val hasHunksOrChanges = lines.any { it.startsWith("@@") } ||
            lines.any { (it.startsWith("+") && !it.startsWith("+++")) || (it.startsWith("-") && !it.startsWith("---")) }
        return hasHeader || hasHunksOrChanges
    }

    private fun shouldUseCodeBlock(toolName: String?, content: String, status: RenderStatus): Boolean {
        if (status.isError) return false
        val normalizedTool = toolName?.lowercase()

        if (normalizedTool in setOf("read_file", "file_search", "grep_search", "read_directory")) {
            return true
        }

        return looksLikeCode(content)
    }

    private fun inferLanguage(
        toolName: String?,
        metadata: Map<*, *>?,
        relatedToolCallInfo: RelatedToolCallInfo? = null
    ): String {
        val path = extractPath(metadata, relatedToolCallInfo)
        return when (toolName?.lowercase()) {
            "read_file", "grep_search" -> factory.inferLanguageFromPath(path)
            "file_search", "read_directory" -> "text"
            else -> "text"
        }
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
        return extractPathFromMap(metadata)
            ?: relatedToolCallInfo?.path
    }

    private fun extractPathFromMap(metadata: Map<*, *>?): String? {
        return PATH_KEYS
            .firstNotNullOfOrNull { key ->
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
                    }
                )
            }
            .firstOrNull()
    }

    private fun buildToolResultTitle(toolName: String?, metadata: Map<*, *>?): String {
        if (toolName.equals("invoke_subagent", ignoreCase = true)) {
            val subagentName = metadata?.get("subagent_name")?.toString()
                ?: metadata?.get("subagent")?.toString()
            return if (!subagentName.isNullOrBlank()) {
                "Subagent Result \u2022 $subagentName"
            } else {
                "Subagent Result"
            }
        }

        return if (toolName != null) {
            "Tool Result \u2022 $toolName"
        } else {
            "Tool Result"
        }
    }

    private fun resolveStatus(content: String): RenderStatus {
        val lower = content.lowercase()
        val isError = lower.startsWith("error") ||
            lower.contains(" failed") ||
            lower.contains("error:")

        return if (isError) {
            RenderStatus(isError = true, icon = "\u2717")
        } else {
            RenderStatus(isError = false, icon = "\u2713")
        }
    }

    private fun normalizeText(content: String): String {
        return content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseMetadata(metadata: String?): Map<*, *>? {
        if (metadata.isNullOrBlank()) return null
        return try {
            Gson().fromJson(metadata, TypeToken.get(Map::class.java).type) as? Map<*, *>
        } catch (_: Exception) {
            null
        }
    }

    private fun buildToolCallIdToNameMap(messages: List<Message>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        messages.forEach { msg ->
            val info = msg.toolCallInfo
            if (msg.role == "assistant" && info != null) {
                map[info.toolCallId] = info.toolName
            }
        }
        return map
    }

    private fun looksLikeCode(content: String): Boolean {
        val lines = content.lines()
        if (lines.size < 5) return false

        val hasCodeMarkers = content.count { it in setOf('<', '>', '{', '}', '(', ')', ';') } > 20
        val hasIndentation = lines.count { it.startsWith("  ") || it.startsWith("\t") } > lines.size / 4
        return hasCodeMarkers || hasIndentation
    }

    private fun isDiffContent(content: String): Boolean {
        val lines = content.lines()
        val hasDiffOldMarker = lines.any { it.startsWith("--- ") }
        val hasDiffNewMarker = lines.any { it.startsWith("+++ ") }
        val hasHunkHeader = lines.any { line ->
            line.startsWith("@@") && line.length > 4 && line.indexOf("@@", 2) > 2
        }
        val hasChangedLines = lines.any {
            (it.startsWith("+") && !it.startsWith("+++")) ||
                (it.startsWith("-") && !it.startsWith("---"))
        }
        return hasDiffOldMarker && hasDiffNewMarker && hasHunkHeader && hasChangedLines
    }

    private fun wrapDiffInCodeBlock(content: String): String {
        if (content.contains("```diff")) return content

        val diffMarkers = listOf("Diff:", "Diff:\n", "diff:", "diff:\n")
        var diffStartIndex = -1
        var markerLength = 0

        for (marker in diffMarkers) {
            val index = content.indexOf(marker)
            if (index != -1) {
                diffStartIndex = index
                markerLength = marker.length
                break
            }
        }

        return if (diffStartIndex != -1) {
            val beforeDiff = content.substring(0, diffStartIndex + markerLength)
            val diffContent = content.substring(diffStartIndex + markerLength).trim()
            "$beforeDiff\n```diff\n$diffContent\n```"
        } else {
            val lines = content.lines()
            val diffLineIndex = lines.indexOfFirst { it.startsWith("---") || it.startsWith("@@") }
            if (diffLineIndex > 0) {
                val beforeDiff = lines.take(diffLineIndex).joinToString("\n")
                val diffContent = lines.drop(diffLineIndex).joinToString("\n")
                "$beforeDiff\n```diff\n$diffContent\n```"
            } else {
                "```diff\n$content\n```"
            }
        }
    }
}
