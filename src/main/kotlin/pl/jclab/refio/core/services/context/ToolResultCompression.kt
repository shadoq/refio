package pl.jclab.refio.core.services.context

data class ToolResultCompressionConfig(
    val detailedMaxChars: Int,
    val summaryMaxChars: Int
)

object ToolResultCompression {
    fun compress(
        rawOutput: String,
        summary: String?,
        level: CompressionLevel,
        config: ToolResultCompressionConfig
    ): String {
        val raw = rawOutput.ifBlank { "-" }
        val summaryOrRaw = if (!summary.isNullOrBlank()) summary else raw

        return when (level) {
            CompressionLevel.FULL -> raw
            // DETAILED should preserve real tool output when budget allows.
            CompressionLevel.DETAILED -> smartCompress(raw, config.detailedMaxChars)
            CompressionLevel.SUMMARY -> truncate(summaryOrRaw, config.summaryMaxChars)
        }
    }

    /**
     * Smart compression that preserves document structure.
     * Uses first + last approach for large content.
     */
    private fun smartCompress(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text

        // For very short limits, use simple truncate
        if (maxChars < 200) {
            return truncate(text, maxChars)
        }

        // Check if text has structural elements (code blocks, headers, lists)
        val hasStructure = text.contains("```") ||
                text.contains("<!DOCTYPE") ||
                text.contains("<html") ||
                Regex("^#{1,6}\\s").containsMatchIn(text) ||
                Regex("^\\s*[-*+]\\s").containsMatchIn(text)

        return if (hasStructure) {
            compressWithStructure(text, maxChars)
        } else {
            compressFirstAndLast(text, maxChars)
        }
    }

    /**
     * Compress text while preserving structural elements like code blocks, HTML tags, etc.
     * Takes first 60% and last 40% within the character limit.
     */
    private fun compressWithStructure(text: String, maxChars: Int): String {
        val lines = text.lines()
        if (lines.size <= 20) {
            return truncate(text, maxChars)
        }

        // Allocate 60% for beginning, 40% for end
        val firstPartChars = (maxChars * 0.6).toInt()
        val lastPartChars = maxChars - firstPartChars - 50  // -50 for separator

        // Build first part (from beginning)
        val firstPart = buildString {
            var currentChars = 0
            for (line in lines) {
                val lineWithNewline = if (currentChars > 0) "\n$line" else line
                if (currentChars + lineWithNewline.length > firstPartChars) break
                append(lineWithNewline)
                currentChars += lineWithNewline.length
            }
        }

        // Build last part (from end)
        val lastPart = buildString {
            var currentChars = 0
            val reversedLines = lines.asReversed()
            for (line in reversedLines) {
                val lineWithNewline = if (currentChars > 0) "$line\n" else line
                if (currentChars + lineWithNewline.length > lastPartChars) break
                insert(0, lineWithNewline)
                currentChars += lineWithNewline.length
            }
        }

        // Add separator with context info
        val totalLines = lines.size
        val firstLineCount = firstPart.lines().size
        val lastLineCount = lastPart.lines().size
        val omittedLines = totalLines - firstLineCount - lastLineCount

        val separator = when {
            omittedLines > 100 -> "\n\n... [$omittedLines lines omitted] ...\n\n"
            omittedLines > 0 -> "\n\n... [$omittedLines more lines] ...\n\n"
            else -> "\n\n"
        }

        return (firstPart + separator + lastPart).trim()
    }

    /**
     * Simple first + last compression for unstructured text.
     */
    private fun compressFirstAndLast(text: String, maxChars: Int): String {
        val half = maxChars / 2

        // Take first half
        val first = text.take(half)

        // Take last half (from end)
        val last = text.takeLast(half)

        // Find good break point (newline or space)
        val firstBreak = findLastBreakPoint(first)
        val lastBreak = findFirstBreakPoint(last)

        val omitted = text.length - firstBreak.length - lastBreak.length

        return buildString {
            append(firstBreak)
            append("\n\n... [$omitted chars omitted] ...\n\n")
            append(lastBreak)
        }
    }

    private fun findLastBreakPoint(text: String): String {
        // Try to find newline, then space
        val newlineIndex = text.lastIndexOf('\n')
        if (newlineIndex > text.length / 2) {
            return text.substring(0, newlineIndex)
        }

        val spaceIndex = text.lastIndexOf(' ')
        if (spaceIndex > text.length / 2) {
            return text.substring(0, spaceIndex)
        }

        return text
    }

    private fun findFirstBreakPoint(text: String): String {
        // Try to find newline, then space
        val newlineIndex = text.indexOf('\n')
        if (newlineIndex >= 0 && newlineIndex < text.length / 2) {
            return text.substring(newlineIndex + 1)
        }

        val spaceIndex = text.indexOf(' ')
        if (spaceIndex >= 0 && spaceIndex < text.length / 2) {
            return text.substring(spaceIndex + 1)
        }

        return text
    }

    private fun truncate(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text

        val suffix = "..."
        if (maxChars <= suffix.length) return text.take(maxChars)
        return text.take(maxChars - suffix.length) + suffix
    }
}
