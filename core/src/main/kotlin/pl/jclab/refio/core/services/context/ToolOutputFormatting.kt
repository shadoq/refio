package pl.jclab.refio.core.services.context

import pl.jclab.refio.core.models.context.ExecutedStepDTO
import java.nio.file.Path

/**
 * Tool-output rendering helpers for context sections.
 * Pure functions extracted from ContextService: format a tool execution result
 * as an XML-like tag with metadata attributes and a fenced markdown body,
 * plus generic truncation / language-detection utilities.
 */
internal object ToolOutputFormatting {

    fun formatToolOutput(
        step: ExecutedStepDTO,
        level: CompressionLevel,
        includeMetadata: Boolean,
        compressionConfig: ToolResultCompressionConfig
    ): String {
        val fileAttr = buildToolFileAttribute(step, includeMetadata)
        val content = ToolResultCompression.compress(step.result, step.summary, level, compressionConfig, step.subtaskId)
        val tagSuffix = if (fileAttr.isNotBlank()) " $fileAttr" else ""

        // Add compression level attribute (only show if not FULL)
        val compressionAttr = if (level != CompressionLevel.FULL) {
            " compressed=\"${level.name.lowercase()}\""
        } else {
            ""
        }

        // Add metadata: timestamp, params (truncated), summary
        val timestamp = step.timestamp.toString().take(19)  // ISO format, truncate milliseconds
        val paramsAttr = formatToolParamsAttribute(step.parameters)
        val summaryAttr = if (!step.summary.isNullOrBlank() && step.summary.length <= 100) {
            " summary=\"${step.summary.replace("\"", "'")}\""
        } else {
            ""
        }
        val subtaskIdAttr = " subtaskId=\"${step.subtaskId.replace("\"", "'")}\""

        return buildString {
            append("<tool name=\"")
            append(step.tool)
            append("\"")
            append(tagSuffix)
            append(compressionAttr)
            append(subtaskIdAttr)
            append(" timestamp=\"")
            append(timestamp)
            append("\"")
            if (paramsAttr.isNotBlank()) append(paramsAttr)
            if (summaryAttr.isNotBlank()) append(summaryAttr)
            append(">\n")
            append(wrapInMarkdownCodeBlock(content.ifBlank { "-" }))
            append("\n</tool>")
        }
    }

    fun wrapInMarkdownCodeBlock(content: String): String {
        val fenceLength = maxOf(3, longestBacktickRun(content) + 1)
        val fence = "`".repeat(fenceLength)
        return buildString {
            append(fence)
            append("text\n")
            append(content)
            append("\n")
            append(fence)
        }
    }

    fun longestBacktickRun(text: String): Int {
        var longest = 0
        var current = 0
        for (char in text) {
            if (char == '`') {
                current += 1
                if (current > longest) longest = current
            } else {
                current = 0
            }
        }
        return longest
    }

    fun formatToolParamsAttribute(
        parameters: Map<String, Any>,
        maxParams: Int = 5,
        maxValueLength: Int = 80,
        maxAttributeLength: Int = 320
    ): String {
        if (parameters.isEmpty()) return ""

        val visibleEntries = parameters.entries.take(maxParams)
        val paramsStr = visibleEntries.joinToString(",") { (key, value) ->
            val safeKey = sanitizeXmlAttributeValue(key)
            val safeValue = sanitizeXmlAttributeValue(truncateValue(value.toString(), maxValueLength))
            "$safeKey=$safeValue"
        }

        val withCountSuffix = if (parameters.size > maxParams) {
            "$paramsStr,+${parameters.size - maxParams}_more"
        } else {
            paramsStr
        }

        val trimmed = truncateValue(withCountSuffix, maxAttributeLength)
        return if (trimmed.isBlank()) "" else " params=\"$trimmed\""
    }

    fun sanitizeXmlAttributeValue(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "'")
            .replace("\n", " ")
            .replace("\r", " ")
            .trim()
    }

    fun truncateValue(value: String, maxLength: Int): String {
        if (maxLength <= 0) return ""
        return if (value.length > maxLength) {
            "${value.take(maxLength)}..."
        } else {
            value
        }
    }

    fun buildToolFileAttribute(step: ExecutedStepDTO, includeMetadata: Boolean): String {
        val filePath = step.file ?: return ""
        if (!includeMetadata) return "file=\"$filePath\""

        val path = Path.of(filePath)
        val size = try {
            val bytes = java.nio.file.Files.size(path)
            when {
                bytes < 1024 -> "${bytes}B"
                bytes < 1024 * 1024 -> "${bytes / 1024}KB"
                else -> "${bytes / (1024 * 1024)}MB"
            }
        } catch (e: Exception) {
            "?"
        }
        val ext = path.fileName.toString().substringAfterLast('.', "").takeIf { it.isNotEmpty() } ?: "txt"
        return "file=\"$filePath\" size=\"$size\" type=\"$ext\""
    }

    /**
     * Truncate text to specified length with ellipsis.
     */
    /**
     * Intelligently truncate text, with special handling for code blocks.
     * Detects markdown code blocks and truncates them with summary instead of raw cut.
     */
    fun truncate(text: String, maxLength: Int): String {
        if (text.length <= maxLength) {
            return text
        }

        // Detect code blocks (``` ... ```)
        val codeBlockRegex = Regex("```[\\w]*\\n([\\s\\S]*?)```", RegexOption.MULTILINE)
        val hasCodeBlocks = codeBlockRegex.containsMatchIn(text)

        if (hasCodeBlocks) {
            val parts = mutableListOf<String>()
            var lastIndex = 0
            var totalLength = 0

            codeBlockRegex.findAll(text).forEach { match ->
                // Add text before code block
                val beforeCode = text.substring(lastIndex, match.range.first)
                if (beforeCode.isNotBlank()) {
                    val available = maxLength - totalLength
                    if (available > 0) {
                        val truncated = if (beforeCode.length > available) {
                            beforeCode.take(available) + "..."
                        } else {
                            beforeCode
                        }
                        parts.add(truncated)
                        totalLength += truncated.length
                    }
                }

                // Process code block
                val codeBlock = match.value
                val codeContent = match.groupValues[1]
                val lines = codeContent.lines()
                val available = maxLength - totalLength

                if (available > 50) {  // Minimum space for code preview
                    if (lines.size <= 10) {
                        // Short code block - include it fully if space allows
                        if (codeBlock.length <= available) {
                            parts.add(codeBlock)
                            totalLength += codeBlock.length
                        } else {
                            val previewLines = lines.take(5).joinToString("\n")
                            val preview = "```\n$previewLines\n... (${lines.size - 5} more lines)\n```"
                            parts.add(preview)
                            totalLength += preview.length
                        }
                    } else {
                        // Large code block - show summary
                        val previewLines = lines.take(5).joinToString("\n")
                        val language = match.value.removePrefix("```").substringBefore("\n")
                        val preview =
                            "```$language\n$previewLines\n... (${lines.size - 5} more lines, ${codeContent.length} chars total)\n```"
                        parts.add(preview)
                        totalLength += preview.length
                    }
                } else {
                    // Not enough space - add summary only
                    parts.add("[Code block: ${lines.size} lines, ${codeContent.length} chars]")
                    totalLength += 50
                }

                lastIndex = match.range.last + 1
            }

            // Add remaining text after last code block
            if (lastIndex < text.length) {
                val remaining = text.substring(lastIndex)
                val available = maxLength - totalLength
                if (available > 0 && remaining.isNotBlank()) {
                    val truncated = if (remaining.length > available) {
                        remaining.take(available) + "..."
                    } else {
                        remaining
                    }
                    parts.add(truncated)
                }
            }

            return parts.joinToString("")
        }

        // No code blocks - simple truncation
        return "${text.take(maxLength)}..."
    }

    /**
     * Detect programming language from file path.
     */
    fun detectLanguage(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt" -> "Kotlin"
            "java" -> "Java"
            "py" -> "Python"
            "js" -> "JavaScript"
            "ts", "tsx" -> "TypeScript"
            "jsx" -> "React"
            "html", "htm" -> "HTML"
            "css", "scss", "sass", "less" -> "CSS"
            "md", "markdown" -> "Markdown"
            "json" -> "JSON"
            "xml" -> "XML"
            "yaml", "yml" -> "YAML"
            "sql" -> "SQL"
            "sh", "bash" -> "Shell"
            "rs" -> "Rust"
            "go" -> "Go"
            "cpp", "cc", "cxx" -> "C++"
            "c", "h" -> "C"
            "cs" -> "C#"
            "rb" -> "Ruby"
            "php" -> "PHP"
            "swift" -> "Swift"
            else -> ext.uppercase().takeIf { it.isNotEmpty() } ?: "Unknown"
        }
    }

    /**
     * Estimate code complexity based on lines and nesting level.
     */
    fun estimateComplexity(content: String): String {
        val lines = content.lines().size
        val nestingLevel = content.count { it == '{' || it == '(' }

        return when {
            lines < 20 && nestingLevel < 5 -> "low"
            lines < 100 && nestingLevel < 20 -> "medium"
            else -> "high"
        }
    }
}
