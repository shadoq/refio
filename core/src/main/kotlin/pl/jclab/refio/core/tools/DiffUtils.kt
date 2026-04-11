package pl.jclab.refio.core.tools

import pl.jclab.refio.core.tools.base.ChangeSummary
import java.security.MessageDigest

/**
 * Shared diff utilities for write tools.
 *
 * Single source of truth for unified-diff generation, hash computation and
 * [ChangeSummary] construction. Replaces the duplicate implementations that
 * previously lived inside [pl.jclab.refio.core.tools.implementations.AdvanceCodeEditingTool]
 * and [pl.jclab.refio.core.tools.implementations.MultiLineEditorTool].
 *
 * Diff format note: this util emits a slightly non-standard unified diff with a
 * 2-character line prefix ("  ", "- ", "+ ") because the rest of Refio (UI bubbles,
 * `parseDiffStats`) is already coupled to that format.
 */
object DiffUtils {

    private const val DEFAULT_CONTEXT_LINES = 3

    /**
     * Generate a unified diff between [originalContent] and [newContent] using a
     * Myers-style LCS algorithm with [contextLines] lines of context per hunk.
     */
    fun generateUnifiedDiff(
        originalContent: String,
        newContent: String,
        filePath: String,
        contextLines: Int = DEFAULT_CONTEXT_LINES
    ): String {
        val original = originalContent.lines()
        val updated = newContent.lines()
        val diffEntries = buildDiffEntries(original, updated)
        val hunks = buildDiffHunks(diffEntries, contextLines)

        return buildString {
            appendLine("--- a/$filePath")
            appendLine("+++ b/$filePath")
            for (hunk in hunks) {
                appendLine("@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@")
                for (entry in hunk.lines) {
                    when (entry.type) {
                        DiffEntryType.CONTEXT -> appendLine("  ${entry.content}")
                        DiffEntryType.DELETE -> appendLine("- ${entry.content}")
                        DiffEntryType.INSERT -> appendLine("+ ${entry.content}")
                    }
                }
            }
        }
    }

    /**
     * Count added/removed lines in a unified diff produced by [generateUnifiedDiff].
     * Counts only data lines (prefixed by "+ " / "- "), not the file headers.
     */
    fun parseDiffStats(diff: String): Pair<Int, Int> {
        var added = 0
        var removed = 0
        diff.lineSequence().forEach { line ->
            when {
                line.startsWith("+ ") -> added++
                line.startsWith("- ") -> removed++
            }
        }
        return added to removed
    }

    /**
     * Build a [ChangeSummary] from before/after content. Computes diff, hashes and stats.
     *
     * @param originalContent file content before the edit (empty string for newly-created files)
     * @param newContent file content after the edit
     * @param filePath relative path used in diff headers
     * @param replacements optional replacement count for search-and-replace tools
     * @param created whether the file was newly created
     */
    fun buildChangeSummary(
        originalContent: String,
        newContent: String,
        filePath: String,
        replacements: Int? = null,
        created: Boolean = false
    ): ChangeSummary {
        val diff = generateUnifiedDiff(originalContent, newContent, filePath)
        val (added, removed) = parseDiffStats(diff)
        return ChangeSummary(
            addedLines = added,
            removedLines = removed,
            unifiedDiff = diff,
            oldHash = if (created && originalContent.isEmpty()) null else sha256(originalContent),
            newHash = sha256(newContent),
            replacements = replacements,
            created = created
        )
    }

    /**
     * SHA-256 hex digest of the given content (UTF-8 bytes).
     */
    fun sha256(content: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (b in bytes) append("%02x".format(b))
        }
    }

    // ----- internal Myers / hunking implementation -----

    private data class DiffEntry(
        val type: DiffEntryType,
        val content: String,
        val oldLine: Int?,
        val newLine: Int?
    )

    private enum class DiffEntryType { CONTEXT, DELETE, INSERT }

    private data class DiffHunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val lines: List<DiffEntry>
    )

    private fun buildDiffEntries(original: List<String>, updated: List<String>): List<DiffEntry> {
        val m = original.size
        val n = updated.size
        val lcs = Array(m + 1) { IntArray(n + 1) }

        for (i in m - 1 downTo 0) {
            for (j in n - 1 downTo 0) {
                lcs[i][j] = if (original[i] == updated[j]) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }

        val result = mutableListOf<DiffEntry>()
        var i = 0
        var j = 0

        while (i < m && j < n) {
            when {
                original[i] == updated[j] -> {
                    result.add(DiffEntry(DiffEntryType.CONTEXT, original[i], i, j))
                    i++; j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> {
                    result.add(DiffEntry(DiffEntryType.DELETE, original[i], i, null))
                    i++
                }
                else -> {
                    result.add(DiffEntry(DiffEntryType.INSERT, updated[j], null, j))
                    j++
                }
            }
        }
        while (i < m) {
            result.add(DiffEntry(DiffEntryType.DELETE, original[i], i, null))
            i++
        }
        while (j < n) {
            result.add(DiffEntry(DiffEntryType.INSERT, updated[j], null, j))
            j++
        }
        return result
    }

    private fun buildDiffHunks(entries: List<DiffEntry>, contextLines: Int): List<DiffHunk> {
        if (entries.isEmpty()) return emptyList()
        val changedIndices = entries.indices.filter { entries[it].type != DiffEntryType.CONTEXT }
        if (changedIndices.isEmpty()) return emptyList()

        val mergedRanges = mutableListOf<IntRange>()
        for (index in changedIndices) {
            val rangeStart = maxOf(0, index - contextLines)
            val rangeEnd = minOf(entries.lastIndex, index + contextLines)
            if (mergedRanges.isEmpty()) {
                mergedRanges.add(rangeStart..rangeEnd)
                continue
            }
            val previous = mergedRanges.last()
            if (rangeStart <= previous.last + 1) {
                mergedRanges[mergedRanges.lastIndex] = previous.first..maxOf(previous.last, rangeEnd)
            } else {
                mergedRanges.add(rangeStart..rangeEnd)
            }
        }

        return mergedRanges.map { range ->
            val hunkEntries = entries.subList(range.first, range.last + 1)
            val oldStart = (hunkEntries.firstNotNullOfOrNull { it.oldLine }
                ?: hunkEntries.firstNotNullOfOrNull { it.newLine }
                ?: 0) + 1
            val newStart = (hunkEntries.firstNotNullOfOrNull { it.newLine }
                ?: hunkEntries.firstNotNullOfOrNull { it.oldLine }
                ?: 0) + 1
            DiffHunk(
                oldStart = oldStart,
                oldCount = hunkEntries.count { it.oldLine != null },
                newStart = newStart,
                newCount = hunkEntries.count { it.newLine != null },
                lines = hunkEntries.toList()
            )
        }
    }
}
