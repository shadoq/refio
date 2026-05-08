package pl.jclab.refio.core.services.context

/**
 * Content-aware compressor for unified-diff bodies inside tool results.
 *
 * Tools like `advance_code_editing` emit the entire generated file as a single
 * hunk of `+` lines when creating new files. For a 700-line HTML page that is
 * ~6.5K tokens carried into RECENT_WORK on the very next agent turn — repeating
 * content the agent already asked the tool to produce. The information value
 * to the next turn is "what was the shape of the change", not "what is every
 * byte"; full content stays accessible through `memory(get_subtask_output)`.
 *
 * Compression rules (applied per fenced ```diff block):
 *   1. Diffs with `+/- lines combined ≤ SMALL_DIFF_THRESHOLD` pass through.
 *   2. Pure-create (no `-` lines, many `+`) keeps headers + head/tail preview
 *      and elides the bulk with a recovery marker.
 *   3. Large mixed diffs keep every `+` and `-` line (semantic delta) but
 *      elide context lines (` ` prefix) beyond a tiny per-hunk buffer.
 *
 * Anything outside ```diff fences is untouched. Tool-result headers like
 * "File created successfully", "Size: ...", and added-symbols summaries flow
 * through as-is so the agent keeps the high-signal context.
 */
object DiffCompressor {

    private const val SMALL_DIFF_THRESHOLD = 100
    private const val CREATE_PREVIEW_HEAD = 15
    private const val CREATE_PREVIEW_TAIL = 8
    private const val MIXED_CONTEXT_LINES = 1

    private val DIFF_FENCE = Regex("```diff\\n([\\s\\S]*?)```", RegexOption.MULTILINE)

    /**
     * Compress every fenced ```diff block inside [text]. When [subtaskId] is
     * provided the recovery hint embeds the literal id so the agent can copy
     * it straight into a `memory(get_subtask_output)` call; otherwise the hint
     * tells the agent to look at the surrounding `<tool subtaskId="…">` tag.
     */
    fun compress(text: String, subtaskId: String? = null): String {
        if (!text.contains("```diff")) return text
        return DIFF_FENCE.replace(text) { match ->
            val body = match.groupValues[1]
            val compressed = compressDiffBody(body, subtaskId)
            if (compressed === body) match.value else "```diff\n$compressed\n```"
        }
    }

    private fun compressDiffBody(body: String, subtaskId: String?): String {
        val lines = body.lines()
        var plusCount = 0
        var minusCount = 0
        for (line in lines) {
            when {
                line.startsWith("+++") || line.startsWith("---") -> Unit
                line.startsWith("+") -> plusCount++
                line.startsWith("-") -> minusCount++
            }
        }

        if (plusCount + minusCount <= SMALL_DIFF_THRESHOLD) return body

        return if (minusCount == 0) {
            compressPureCreate(lines, plusCount, subtaskId)
        } else {
            compressLargeMixed(lines, plusCount, minusCount)
        }
    }

    /**
     * Pure-create case: original file was empty / placeholder, the whole new
     * file is delivered as `+` lines. Keep headers (file paths + hunk markers)
     * and a head/tail preview of the new content so the agent sees the shape;
     * elide the middle with a `memory(get_subtask_output)` hint that, when
     * [subtaskId] is known, contains the literal id ready to copy-paste.
     */
    private fun compressPureCreate(lines: List<String>, plusCount: Int, subtaskId: String?): String {
        val headers = lines.takeWhile {
            it.startsWith("---") || it.startsWith("+++") || it.startsWith("@@")
        }
        val plusLines = lines.filter { it.startsWith("+") && !it.startsWith("+++") }

        val head = plusLines.take(CREATE_PREVIEW_HEAD)
        val tail = plusLines.takeLast(CREATE_PREVIEW_TAIL)
        val omitted = plusCount - head.size - tail.size
        if (omitted <= 0) return lines.joinToString("\n")

        // Prefer the literal subtaskId in the marker so the agent can copy it
        // into memory(...) without having to scan attributes. Fall back to a
        // pointer at the enclosing tag attribute when the caller doesn't know
        // the id (e.g. compression invoked outside a tool-result formatter).
        val subtaskRef = subtaskId?.let { "\"$it\"" } ?: "\"<see subtaskId attribute above>\""
        return buildString {
            headers.forEach { appendLine(it) }
            head.forEach { appendLine(it) }
            appendLine("<!-- $omitted added line(s) elided (pure-create diff). Full content: memory(action=\"get_subtask_output\", subtask_id=$subtaskRef) -->")
            tail.forEach { appendLine(it) }
        }.trimEnd('\n')
    }

    /**
     * Large mixed-edit case: many `+` and `-` lines interleaved with context.
     * The semantic delta (every change) is preserved verbatim. Only the
     * unchanged ` `-prefixed context surrounding hunks is collapsed to one
     * line, with a `...` marker the first time we drop. Keeps the agent's
     * mental model of "this changed, that did not" intact while reclaiming
     * the bulk of the tokens.
     */
    private fun compressLargeMixed(lines: List<String>, plusCount: Int, minusCount: Int): String {
        val out = StringBuilder()
        var contextRun = 0
        var elidedContext = 0
        for (line in lines) {
            val isHeader = line.startsWith("---") || line.startsWith("+++") || line.startsWith("@@")
            val isContext = !isHeader && (line.startsWith(" ") || line.isEmpty())
            if (isContext) {
                contextRun++
                when {
                    contextRun <= MIXED_CONTEXT_LINES -> out.appendLine(line)
                    contextRun == MIXED_CONTEXT_LINES + 1 -> {
                        out.appendLine("...")
                        elidedContext++
                    }
                    else -> elidedContext++
                }
            } else {
                contextRun = 0
                out.appendLine(line)
            }
        }
        out.append("<!-- $elidedContext context line(s) compressed to ${MIXED_CONTEXT_LINES}-line per hunk; all +$plusCount/-$minusCount semantic changes preserved -->")
        return out.toString().trimEnd('\n')
    }
}
