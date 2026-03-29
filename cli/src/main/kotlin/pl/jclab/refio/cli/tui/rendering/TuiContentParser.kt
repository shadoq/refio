package pl.jclab.refio.cli.tui.rendering

/**
 * Parses assistant message content into typed segments for TUI rendering.
 * Adapted from plugin's ContentSegmentParser (ui/components/chat/ContentSegmentParser.kt).
 *
 * Detection priority:
 * 1. `<think>/<thinking>` tags (closed and unclosed/streaming)
 * 2. Fenced code blocks (```lang:path ... ```) (closed and unclosed/streaming)
 * 3. Standalone JSON objects { ... }
 * 4. Everything else is Markdown
 *
 * Streaming awareness: unclosed tags/fences at end of content produce
 * partial Thinking or Code segments instead of falling through to Markdown.
 */
object TuiContentParser {

    // Closed thinking tags
    private val THINKING_REGEX = Regex(
        """<(think(?:ing)?)>(.*?)</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    // Unclosed thinking tag (streaming)
    private val UNCLOSED_THINKING_REGEX = Regex(
        """<(think(?:ing)?)>(.*)$""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    // Closed fenced code block
    private val CODE_FENCE_REGEX = Regex(
        """```(\w*)(?::([^\n]+))?\s*(.*?)```""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
    )

    // Unclosed code fence (streaming)
    private val UNCLOSED_FENCE_REGEX = Regex(
        """```(\w*)(?::([^\n]+))?\s*(.*)$""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    fun parse(content: String, isStreaming: Boolean = false): List<TuiContentSegment> {
        if (content.isBlank()) return emptyList()

        val anchors = mutableListOf<AnchorMatch>()

        // Find closed thinking tags
        THINKING_REGEX.findAll(content).forEach { match ->
            anchors.add(AnchorMatch(
                type = AnchorType.THINKING,
                range = match.range,
                innerContent = match.groupValues[2].trim()
            ))
        }

        // Find closed code fences
        CODE_FENCE_REGEX.findAll(content).forEach { match ->
            val language = match.groupValues[1].takeIf { it.isNotBlank() } ?: "text"
            val filePath = match.groupValues[2].takeIf { it.isNotBlank() }?.trim()
            val codeContent = match.groupValues[3].trim()
                .replace("\r\n", "\n").replace("\r", "\n")

            anchors.add(AnchorMatch(
                type = AnchorType.CODE,
                range = match.range,
                innerContent = codeContent,
                language = language,
                filePath = filePath
            ))
        }

        // Streaming: check for unclosed tags at the end
        if (isStreaming) {
            val lastAnchorEnd = anchors.maxOfOrNull { it.range.last } ?: -1
            val remainingStart = lastAnchorEnd + 1
            if (remainingStart < content.length) {
                val remaining = content.substring(remainingStart)
                addStreamingAnchor(remaining, remainingStart, content.lastIndex, anchors)
            }
        }

        // Sort by position, deduplicate overlaps (first-match-wins)
        val sorted = anchors
            .sortedBy { it.range.first }
            .fold(mutableListOf<AnchorMatch>()) { acc, anchor ->
                val lastEnd = acc.lastOrNull()?.range?.last ?: -1
                if (anchor.range.first > lastEnd) {
                    acc.add(anchor)
                }
                acc
            }

        // Walk content: emit segments for gaps and anchors
        val segments = mutableListOf<TuiContentSegment>()
        var cursor = 0

        for (anchor in sorted) {
            if (anchor.range.first > cursor) {
                val gapText = content.substring(cursor, anchor.range.first)
                addGapSegments(gapText, segments)
            }

            when (anchor.type) {
                AnchorType.THINKING -> {
                    if (anchor.innerContent.isNotBlank()) {
                        segments.add(TuiContentSegment.Thinking(anchor.innerContent))
                    }
                }
                AnchorType.CODE -> {
                    segments.add(TuiContentSegment.Code(
                        language = anchor.language ?: "text",
                        filePath = anchor.filePath,
                        content = anchor.innerContent
                    ))
                }
            }

            cursor = anchor.range.last + 1
        }

        // Trailing text after last anchor
        if (cursor < content.length) {
            addGapSegments(content.substring(cursor), segments)
        }

        return segments
    }

    private fun addStreamingAnchor(
        remaining: String,
        absoluteOffset: Int,
        contentLastIndex: Int,
        anchors: MutableList<AnchorMatch>
    ) {
        val unclosedThinking = UNCLOSED_THINKING_REGEX.find(remaining)
        if (unclosedThinking != null) {
            val absoluteStart = absoluteOffset + unclosedThinking.range.first
            anchors.add(AnchorMatch(
                type = AnchorType.THINKING,
                range = absoluteStart..contentLastIndex,
                innerContent = unclosedThinking.groupValues[2].trim()
            ))
            return
        }

        val unclosedFence = UNCLOSED_FENCE_REGEX.find(remaining)
        if (unclosedFence != null) {
            val absoluteStart = absoluteOffset + unclosedFence.range.first
            anchors.add(AnchorMatch(
                type = AnchorType.CODE,
                range = absoluteStart..contentLastIndex,
                innerContent = unclosedFence.groupValues[3].trim()
                    .replace("\r\n", "\n").replace("\r", "\n"),
                language = unclosedFence.groupValues[1].takeIf { it.isNotBlank() } ?: "text",
                filePath = unclosedFence.groupValues[2].takeIf { it.isNotBlank() }?.trim()
            ))
        }
    }

    private fun addGapSegments(text: String, segments: MutableList<TuiContentSegment>) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        if (trimmed.startsWith("{") && trimmed.endsWith("}") && isLikelyJson(trimmed)) {
            segments.add(TuiContentSegment.Json(trimmed))
        } else {
            segments.add(TuiContentSegment.Markdown(text))
        }
    }

    /** Simple JSON detection without Gson — counts balanced braces. */
    private fun isLikelyJson(text: String): Boolean {
        var depth = 0
        for (c in text) {
            when (c) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth < 0) return false
        }
        return depth == 0 && text.contains("\"")
    }

    private enum class AnchorType { THINKING, CODE }

    private data class AnchorMatch(
        val type: AnchorType,
        val range: IntRange,
        val innerContent: String,
        val language: String? = null,
        val filePath: String? = null
    )
}
