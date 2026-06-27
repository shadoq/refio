package pl.jclab.refio.core.services.context

data class ToolResultCompressionConfig(
    val detailedMaxChars: Int,
    val summaryMaxChars: Int
)

object ToolResultCompression {
    /**
     * @param subtaskId optional id of the subtask that produced [rawOutput] —
     *   forwarded to [DiffCompressor] so the elision marker can embed the
     *   literal id in `memory(get_subtask_output, subtask_id="…")` instead
     *   of pointing at the surrounding tag attribute.
     */
    fun compress(
        rawOutput: String,
        summary: String?,
        level: CompressionLevel,
        config: ToolResultCompressionConfig,
        subtaskId: String? = null
    ): String {
        val raw = rawOutput.ifBlank { "-" }
        val summaryOrRaw = if (!summary.isNullOrBlank()) summary else raw

        val body = when (level) {
            // Even at FULL we apply diff-body compression: a 700-line +diff carries
            // no information the agent doesn't already have (it just generated it),
            // so eliding the body is lossless from the agent's perspective. The full
            // tool output stays accessible via memory(get_subtask_output).
            CompressionLevel.FULL -> DiffCompressor.compress(raw, subtaskId)
            // DETAILED should preserve real tool output when budget allows; same
            // diff-body compression applies before the char-budget head/tail cut.
            CompressionLevel.DETAILED -> smartCompress(DiffCompressor.compress(raw, subtaskId), config.detailedMaxChars)
            CompressionLevel.SUMMARY -> headTailTruncate(summaryOrRaw, config.summaryMaxChars)
        }
        return withRecoveryHint(body, raw, level, subtaskId)
    }

    /**
     * docs/0063 Faza 2 — when the emitted [body] shows the agent LESS than the full [raw] tool
     * output, append a one-line pointer to the full content so the model knows the result was
     * shortened and can pull it back via `memory(get_subtask_output)` instead of hallucinating on a
     * truncated view (the long-turn failure mode). No-ops when nothing was cut, when there is no
     * [subtaskId] to reference, or when a pointer is already present (DiffCompressor adds its own for
     * diff bodies — never double-mark).
     */
    private fun withRecoveryHint(
        body: String,
        raw: String,
        level: CompressionLevel,
        subtaskId: String?
    ): String {
        if (subtaskId.isNullOrBlank()) return body
        if (body.length >= raw.length) return body
        if (body.contains("get_subtask_output")) return body
        return body +
            "\n[result compressed ${raw.length}→${body.length} chars via $level — " +
            "full output: memory(action=\"get_subtask_output\", subtask_id=\"$subtaskId\")]"
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
     *
     * For HTML inputs the head and tail are typically dominated by `<style>`
     * (and sometimes `<script>`) blocks while the data the agent actually
     * needs lives in the body. We strip those blocks BEFORE applying head+tail
     * compression so the body has a fighting chance of surviving the budget.
     */
    private fun compressWithStructure(text: String, maxChars: Int): String {
        val processed = if (looksLikeHtml(text)) stripHtmlNoise(text) else text

        // If stripping styles/scripts already brought it under the limit, we're done.
        if (processed.length <= maxChars) return processed

        val lines = processed.lines()
        if (lines.size <= 20) {
            return headTailTruncate(processed, maxChars)
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

    private fun looksLikeHtml(text: String): Boolean {
        // Sample only the first 2KB — enough to catch the doctype/<html> tag
        // without scanning the whole file.
        val head = text.take(2048)
        return head.contains("<!DOCTYPE", ignoreCase = true) ||
                head.contains("<html", ignoreCase = true)
    }

    /**
     * Remove `<style>...</style>` and `<script>...</script>` blocks from an
     * HTML string, replacing each with a short marker that records how much
     * content was dropped. The agent still sees that styles existed (so it
     * doesn't get confused about HTML structure) but the giant CSS bodies
     * that dominate styled pages no longer crowd out the actual content.
     */
    private fun stripHtmlNoise(html: String): String {
        val styleRegex = Regex("(?is)<style[^>]*>.*?</style>")
        val scriptRegex = Regex("(?is)<script[^>]*>.*?</script>")

        val styleMatches = styleRegex.findAll(html).toList()
        val withoutStyles = if (styleMatches.isNotEmpty()) {
            val styleChars = styleMatches.sumOf { it.value.length }
            styleRegex.replace(
                html,
                "<!-- [${styleMatches.size} <style> block(s) stripped, $styleChars chars] -->"
            )
        } else {
            html
        }

        val scriptMatches = scriptRegex.findAll(withoutStyles).toList()
        return if (scriptMatches.isNotEmpty()) {
            val scriptChars = scriptMatches.sumOf { it.value.length }
            scriptRegex.replace(
                withoutStyles,
                "<!-- [${scriptMatches.size} <script> block(s) stripped, $scriptChars chars] -->"
            )
        } else {
            withoutStyles
        }
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
