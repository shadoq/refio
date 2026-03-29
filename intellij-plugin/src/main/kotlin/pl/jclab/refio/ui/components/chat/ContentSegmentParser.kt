package pl.jclab.refio.ui.components.chat

/**
 * Parses assistant message content into typed segments.
 *
 * Detection priority (left-to-right):
 * 1. <think>...</think> or <thinking>...</thinking> tags
 * 2. ```lang:path ... ``` fenced code blocks
 * 3. Standalone JSON objects { ... } (entire gap is valid JSON)
 * 4. Everything else is Markdown
 *
 * Handles streaming: unclosed tags/fences at end of content produce
 * partial Thinking or Code segments instead of falling through to Markdown.
 */
object ContentSegmentParser {

    // Closed thinking tags
    private val THINKING_REGEX = Regex(
        """<(think(?:ing)?)>(.*?)</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    // Unclosed thinking tag (streaming) — matches to end of string
    private val UNCLOSED_THINKING_REGEX = Regex(
        """<(think(?:ing)?)>(.*)$""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    // Closed fenced code block (same pattern as extractCodeBlocks in CodeBlock.kt)
    private val CODE_FENCE_REGEX = Regex(
        """```(\w*)(?::([^\n]+))?\s*(.*?)```""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
    )

    // Unclosed code fence (streaming) — matches to end of string
    private val UNCLOSED_FENCE_REGEX = Regex(
        """```(\w*)(?::([^\n]+))?\s*(.*)$""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    fun parse(content: String, isStreaming: Boolean = false): List<ContentSegment> {
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

        // Streaming: check for unclosed thinking tag or code fence at the end
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
        val segments = mutableListOf<ContentSegment>()
        var cursor = 0

        for (anchor in sorted) {
            if (anchor.range.first > cursor) {
                val gapText = content.substring(cursor, anchor.range.first)
                addGapSegments(gapText, segments)
            }

            when (anchor.type) {
                AnchorType.THINKING -> {
                    if (anchor.innerContent.isNotBlank()) {
                        segments.add(ContentSegment.Thinking(anchor.innerContent))
                    }
                }
                AnchorType.CODE -> {
                    val codeBlock = CodeBlock(
                        language = anchor.language ?: "text",
                        filePath = anchor.filePath,
                        content = anchor.innerContent,
                        startIndex = anchor.range.first,
                        endIndex = anchor.range.last
                    )
                    segments.add(ContentSegment.Code(codeBlock))
                }
            }

            cursor = anchor.range.last + 1
        }

        // Trailing text after last anchor
        if (cursor < content.length) {
            val trailing = content.substring(cursor)
            addGapSegments(trailing, segments)
        }

        return segments
    }

    private fun addStreamingAnchor(
        remaining: String,
        absoluteOffset: Int,
        contentLastIndex: Int,
        anchors: MutableList<AnchorMatch>
    ) {
        // Try unclosed thinking tag first
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

        // Try unclosed code fence
        val unclosedFence = UNCLOSED_FENCE_REGEX.find(remaining)
        if (unclosedFence != null) {
            val absoluteStart = absoluteOffset + unclosedFence.range.first
            val language = unclosedFence.groupValues[1].takeIf { it.isNotBlank() } ?: "text"
            val filePath = unclosedFence.groupValues[2].takeIf { it.isNotBlank() }?.trim()
            val codeContent = unclosedFence.groupValues[3].trim()
                .replace("\r\n", "\n").replace("\r", "\n")

            anchors.add(AnchorMatch(
                type = AnchorType.CODE,
                range = absoluteStart..contentLastIndex,
                innerContent = codeContent,
                language = language,
                filePath = filePath
            ))
        }
    }

    /**
     * Parse gap text into Markdown, Json, or Plan segment.
     * Only detects JSON if the entire trimmed gap is a valid JSON object.
     * Plan JSON is distinguished by "plan", "subtasks", or "actions" keys.
     */
    private fun addGapSegments(text: String, segments: MutableList<ContentSegment>) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        if (trimmed.startsWith("{") && trimmed.endsWith("}") && isValidJson(trimmed)) {
            val plan = tryParsePlan(trimmed)
            if (plan != null) {
                segments.add(plan)
            } else {
                segments.add(ContentSegment.Json(trimmed))
            }
        } else {
            segments.add(ContentSegment.Markdown(text))
        }
    }

    private fun isValidJson(text: String): Boolean {
        return try {
            val element = com.google.gson.JsonParser.parseString(text)
            element.isJsonObject
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tryParsePlan(json: String): ContentSegment.Plan? {
        return try {
            val map = com.google.gson.Gson().fromJson(
                json,
                com.google.gson.reflect.TypeToken.get(Map::class.java).type
            ) as? Map<*, *> ?: return null

            val hasPlanKeys = map.containsKey("plan") ||
                map.containsKey("subtasks") ||
                map.containsKey("actions")
            if (!hasPlanKeys) return null

            val description = map["plan"] as? String ?: map["response"] as? String
            val subtasks = (map["subtasks"] as? List<*> ?: map["actions"] as? List<*> ?: emptyList<Any>())
                .filterIsInstance<Map<*, *>>()
                .map { step: Map<*, *> -> step.entries.associate { (k, v) -> k.toString() to v } }

            ContentSegment.Plan(description, subtasks)
        } catch (_: Exception) {
            null
        }
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
