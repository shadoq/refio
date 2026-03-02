package pl.jclab.refio.ui.components.chat

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.Session
import java.awt.Component
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

internal object ChatConversationExportUtil {

    private val gson = Gson()

    fun copyConversation(session: Session?, messages: List<Message>) {
        val exportMessages = buildExportMessages(messages)
        val conversationText = buildString {
            appendLine("# Conversation")
            appendLine()
            appendLine("**Session ID:** ${session?.id}")
            appendLine("**Created:** ${formatTimestamp(session?.createdAt)}")
            appendLine()
            appendLine("---")
            appendLine()

            exportMessages.forEach { item ->
                appendLine("## ${item.roleLabel}")
                appendLine()
                appendLine(item.exportContent)
                appendLine()

                item.message.metrics?.let { metrics ->
                    appendLine("*Tokens: ${metrics.inputTokens}/${metrics.outputTokens}, Cost: $${metrics.costUsd}, Latency: ${metrics.latencyMs}ms*")
                    appendLine()
                }

                appendLine("---")
                appendLine()
            }
        }

        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(conversationText), null)
    }

    fun exportConversation(
        parent: Component,
        session: Session,
        messages: List<Message>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val fileChooser = JFileChooser().apply {
            dialogTitle = "Export Conversation"
            fileSelectionMode = JFileChooser.FILES_ONLY
            addChoosableFileFilter(FileNameExtensionFilter("Markdown (*.md)", "md"))
            addChoosableFileFilter(FileNameExtensionFilter("JSON (*.json)", "json"))
            addChoosableFileFilter(FileNameExtensionFilter("Plain Text (*.txt)", "txt"))
            selectedFile = File("conversation-${session.id.take(8)}.md")
        }

        val result = fileChooser.showSaveDialog(parent)
        if (result != JFileChooser.APPROVE_OPTION) return

        val file = fileChooser.selectedFile
        val selectedFilter = fileChooser.fileFilter as? FileNameExtensionFilter
        val filterExtension = selectedFilter?.extensions?.firstOrNull()?.lowercase()
        val extension = if (file.extension.isBlank()) {
            filterExtension ?: "md"
        } else {
            file.extension.lowercase()
        }
        val targetFile = if (file.extension.isBlank() && !filterExtension.isNullOrBlank()) {
            File("${file.absolutePath}.$filterExtension")
        } else {
            file
        }

        runCatching {
            when (extension) {
                "md" -> exportAsMarkdown(targetFile, session, messages)
                "json" -> exportAsJson(targetFile, session, messages)
                "txt" -> exportAsText(targetFile, session, messages)
                else -> error("Unsupported format: $extension")
            }
        }.onSuccess {
            onSuccess(targetFile.name)
        }.onFailure { error ->
            onError(error.message ?: "Export failed")
        }
    }

    private fun exportAsMarkdown(file: File, session: Session, messages: List<Message>) {
        val exportMessages = buildExportMessages(messages)
        file.writeText(
            buildString {
                appendLine("# Conversation Export")
                appendLine()
                appendLine("**Session ID:** ${session.id}")
                appendLine("**Mode:** ${session.mode}")
                appendLine("**Created:** ${formatTimestamp(session.createdAt)}")
                appendLine("**Updated:** ${formatTimestamp(session.updatedAt)}")
                appendLine()
                appendLine("---")
                appendLine()

                exportMessages.forEach { item ->
                    appendLine("## ${item.roleLabel}")
                    appendLine()
                    appendLine(item.exportContent)
                    appendLine()

                    item.message.metrics?.let { metrics ->
                        appendLine("**Metrics:**")
                        appendLine("- Model: ${metrics.model} (${metrics.provider})")
                        appendLine("- Tokens: ${metrics.inputTokens} in / ${metrics.outputTokens} out")
                        appendLine("- Cost: $${"%.4f".format(metrics.costUsd)}")
                        appendLine("- Latency: ${metrics.latencyMs}ms")
                        appendLine()
                    }

                    appendLine("---")
                    appendLine()
                }
            }
        )
    }

    private fun exportAsJson(file: File, session: Session, messages: List<Message>) {
        val exportMessages = messages.map { message ->
            val metadataMap = parseMetadata(message.metadata)
            mapOf(
                "id" to message.id,
                "role" to message.role,
                "roleLabel" to resolveRoleLabel(message, metadataMap),
                "content" to message.content,
                "exportContent" to resolveExportContent(message, metadataMap),
                "thinking" to message.thinking,
                "createdAt" to message.createdAt,
                "metrics" to message.metrics,
                "metadata" to message.metadata,
                "metadataMap" to metadataMap,
                "toolCallId" to message.toolCallId,
                "toolCallInfo" to message.toolCallInfo,
                "toolStreamContent" to message.toolStreamContent,
                "isToolStreaming" to message.isToolStreaming,
                "isStreaming" to message.isStreaming
            )
        }

        val exportData = mapOf(
            "session" to mapOf(
                "id" to session.id,
                "name" to session.name,
                "mode" to session.mode.name,
                "status" to session.status.name,
                "createdAt" to session.createdAt,
                "updatedAt" to session.updatedAt
            ),
            "messages" to exportMessages
        )

        file.writeText(gson.toJson(exportData))
    }

    private fun exportAsText(file: File, session: Session, messages: List<Message>) {
        val exportMessages = buildExportMessages(messages)
        file.writeText(
            buildString {
                appendLine("=".repeat(80))
                appendLine("CONVERSATION EXPORT")
                appendLine("=".repeat(80))
                appendLine()
                appendLine("Session ID: ${session.id}")
                appendLine("Mode: ${session.mode}")
                appendLine("Created: ${formatTimestamp(session.createdAt)}")
                appendLine()
                appendLine("=".repeat(80))
                appendLine()

                exportMessages.forEach { item ->
                    appendLine("[${item.roleLabel.uppercase()}]")
                    appendLine(item.exportContent)
                    appendLine()

                    item.message.metrics?.let { metrics ->
                        appendLine("Tokens: ${metrics.inputTokens}/${metrics.outputTokens}")
                        appendLine("Cost: $${metrics.costUsd}")
                        appendLine("Latency: ${metrics.latencyMs}ms")
                        appendLine()
                    }

                    appendLine("-".repeat(80))
                    appendLine()
                }
            }
        )
    }

    private fun buildExportMessages(messages: List<Message>): List<ExportMessage> {
        return messages.mapNotNull { message ->
            val metadataMap = parseMetadata(message.metadata)
            val exportContent = resolveExportContent(message, metadataMap).trim()
            if (exportContent.isBlank()) return@mapNotNull null

            ExportMessage(
                message = message,
                roleLabel = resolveRoleLabel(message, metadataMap),
                exportContent = exportContent
            )
        }
    }

    private fun resolveRoleLabel(message: Message, metadataMap: Map<*, *>?): String {
        return when (message.role.lowercase()) {
            "user" -> "User"
            "assistant" -> "Assistant"
            "system" -> "System"
            "tool" -> {
                val toolName = resolveToolName(message, metadataMap)
                val subagentName = resolveSubagentName(metadataMap)
                when {
                    toolName.equals("invoke_subagent", ignoreCase = true) && !subagentName.isNullOrBlank() -> {
                        "Tool (invoke_subagent: $subagentName)"
                    }
                    !toolName.isNullOrBlank() -> "Tool ($toolName)"
                    else -> "Tool"
                }
            }
            else -> message.role.ifBlank { "Unknown" }
        }
    }

    private fun resolveExportContent(message: Message, metadataMap: Map<*, *>?): String {
        val content = message.content.trim()
        if (content.isNotBlank()) {
            return content
        }

        val streamedContent = message.toolStreamContent?.trim().orEmpty()
        if (streamedContent.isNotBlank()) {
            return streamedContent
        }

        message.toolCallInfo?.let { info ->
            return buildString {
                appendLine("Tool: ${info.toolName}")
                if (info.parameters.isNotEmpty()) {
                    appendLine("Parameters:")
                    info.parameters.entries
                        .sortedBy { it.key }
                        .forEach { (key, value) -> appendLine("- $key: $value") }
                }
                info.result?.let { result ->
                    appendLine("Result: ${result.summary}")
                }
            }.trim()
        }

        val toolName = resolveToolName(message, metadataMap)
        val subagentName = resolveSubagentName(metadataMap)
        if (!toolName.isNullOrBlank()) {
            return buildString {
                appendLine("Tool: $toolName")
                if (!subagentName.isNullOrBlank()) {
                    appendLine("Subagent: $subagentName")
                }
            }.trim()
        }

        return ""
    }

    private fun resolveToolName(message: Message, metadataMap: Map<*, *>?): String? {
        return message.toolCallInfo?.toolName
            ?: metadataMap?.get("tool_name")?.toString()?.takeIf { it.isNotBlank() }
            ?: metadataMap?.get("tool")?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun resolveSubagentName(metadataMap: Map<*, *>?): String? {
        return metadataMap?.get("subagent_name")?.toString()?.takeIf { it.isNotBlank() }
            ?: metadataMap?.get("subagent")?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun parseMetadata(metadata: String?): Map<*, *>? {
        if (metadata.isNullOrBlank()) return null
        return try {
            gson.fromJson(metadata, TypeToken.get(Map::class.java).type) as? Map<*, *>
        } catch (_: Exception) {
            null
        }
    }

    private fun formatTimestamp(timestamp: Long?): String {
        if (timestamp == null) return "Unknown"
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        return formatter.format(Instant.ofEpochMilli(timestamp))
    }

    private data class ExportMessage(
        val message: Message,
        val roleLabel: String,
        val exportContent: String
    )
}
