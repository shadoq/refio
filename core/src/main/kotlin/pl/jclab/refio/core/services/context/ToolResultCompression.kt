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
            CompressionLevel.SUMMARY -> headTailTruncate(summaryOrRaw, config.summaryMaxChars)
        }
    }

    /**
     * Smart compression that preserves document structure.
     * Uses first + last approach for large content.
     */
    private fun smartCompress(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text

        // For very short limits, fall back to plain head+tail without trying
        // the structure-aware path. headTailTruncate is now safe (preserves
        // both ends with a marker) so this fast path no longer drops tails.
        if (maxChars < 200) {
            return headTailTruncate(text, maxChars)
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
            return headTailTruncate(text, maxChars)
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

    /**
     * Safe head+tail truncation for any content flowing through the
     * summarization / conversation pipeline. If the input already fits, it is
     * returned unchanged. Otherwise the result is `head + omittedMarker + tail`
     * with head and tail roughly equal in size, so trailing data (exit codes,
     * API verify responses, error messages, stack traces) is never silently
     * dropped — the failure mode that previously caused agents to hallucinate
     * success after a failed run_terminal_command.
     *
     * Use this anywhere in the pipeline where you would otherwise reach for
     * `text.take(N)` as a "safety cap". A pure prefix cap is exactly the bug
     * pattern this helper exists to replace.
     *
     * For pathologically small budgets (a few dozen chars) head+tail with a
     * marker can't fit meaningfully, so we fall back to a marked prefix —
     * intentional, because at that size nothing useful survives either way.
     */
    fun headTailTruncate(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text

        // Reserve room for the omitted-bytes marker. We don't know the digit
        // length of `omitted` upfront so reserve a generous fixed budget for
        // the marker itself; any leftover bytes go back into head/tail evenly.
        val markerReserve = 40
        // Below this size head+tail loses meaning — return a marked prefix
        // instead. Bounded fallback by design.
        if (maxChars <= markerReserve + 20) {
            val suffix = "..."
            return if (maxChars > suffix.length) text.take(maxChars - suffix.length) + suffix
                   else text.take(maxChars)
        }

        val available = maxChars - markerReserve
        val headChars = available / 2
        val tailChars = available - headChars
        val omitted = text.length - headChars - tailChars
        val marker = "\n... [$omitted chars omitted] ...\n"
        return text.take(headChars) + marker + text.takeLast(tailChars)
    }
}
