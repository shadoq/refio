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
 *
 * Cost note: the hunks come from a dynamic-programming LCS whose table is O(oldLines * newLines)
 * ints. Two guards keep that off the heap's critical path: the common prefix/suffix is matched
 * first (a small edit in a huge file collapses to a table of a few cells), and a changed region
 * still larger than [MAX_LCS_CELLS] is summarized instead of diffed. Without them a 20k-line file
 * (well inside the 2 MB write-tool limit) would ask for ~1.6 GB and take down the whole JVM with
 * an OutOfMemoryError that no `catch (e: Exception)` can intercept.
 */
object DiffUtils {

    private const val DEFAULT_CONTEXT_LINES = 3

    /**
     * Budget for the LCS table, in cells (~4 bytes each), measured on the changed region that
     * survives prefix/suffix trimming. 4M cells is ~16 MB transient - enough for any edit whose
     * diff a human or a model would still read, and far below the heap an IDE plugin may claim.
     */
    private const val MAX_LCS_CELLS = 4_000_000L

    /** Marker emitted in place of the hunks when the changed region exceeds [MAX_LCS_CELLS]. */
    const val SUPPRESSED_DIFF_MARKER = "diff suppressed"

    /**
     * Diff plus the line counts that produced it.
     *
     * [suppressed] is true when the changed region was too large to diff line by line; the counts
     * are then the size of the changed region, not per-line insert/delete counts.
     */
    data class DiffResult(
        val diff: String,
        val addedLines: Int,
        val removedLines: Int,
        val suppressed: Boolean
    )

    /**
     * Generate a unified diff between [originalContent] and [newContent] with [contextLines] lines
     * of context per hunk, together with its add/remove counts.
     */
    fun computeDiff(
        originalContent: String,
        newContent: String,
        filePath: String,
        contextLines: Int = DEFAULT_CONTEXT_LINES
    ): DiffResult {
        val header = "--- a/$filePath\n+++ b/$filePath\n"

        // Fast path: identical content has no hunks, and skipping the table here is what keeps a
        // no-op rewrite of a huge file cheap.
        if (originalContent == newContent) {
            return DiffResult(diff = header, addedLines = 0, removedLines = 0, suppressed = false)
        }

        val original = originalContent.lines()
        val updated = newContent.lines()

        var prefix = 0
        val minSize = minOf(original.size, updated.size)
        while (prefix < minSize && original[prefix] == updated[prefix]) {
            prefix++
        }
        var suffix = 0
        while (suffix < minSize - prefix &&
            original[original.size - 1 - suffix] == updated[updated.size - 1 - suffix]
        ) {
            suffix++
        }

        val changedOld = original.size - prefix - suffix
        val changedNew = updated.size - prefix - suffix

        if (changedOld.toLong() * changedNew.toLong() > MAX_LCS_CELLS) {
            val diff = buildString {
                append(header)
                appendLine("@@ -${prefix + 1},$changedOld +${prefix + 1},$changedNew @@")
                appendLine(
                    "($SUPPRESSED_DIFF_MARKER: changed region is $changedOld line(s) replaced by " +
                        "$changedNew line(s), too large to render line by line)"
                )
            }
            return DiffResult(diff = diff, addedLines = changedNew, removedLines = changedOld, suppressed = true)
        }

        val entries = buildDiffEntries(original, updated, prefix, suffix)
        val hunks = buildDiffHunks(entries, contextLines)

        val diff = buildString {
            append(header)
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

        return DiffResult(
            diff = diff,
            addedLines = entries.count { it.type == DiffEntryType.INSERT },
            removedLines = entries.count { it.type == DiffEntryType.DELETE },
            suppressed = false
        )
    }

    /**
     * Generate a unified diff between [originalContent] and [newContent] with [contextLines] lines
     * of context per hunk. Prefer [computeDiff] when the caller also needs the line counts.
     */
    fun generateUnifiedDiff(
        originalContent: String,
        newContent: String,
        filePath: String,
        contextLines: Int = DEFAULT_CONTEXT_LINES
    ): String = computeDiff(originalContent, newContent, filePath, contextLines).diff

    /**
     * Count added/removed lines in a unified diff produced by [generateUnifiedDiff].
     * Counts only data lines (prefixed by "+ " / "- "), not the file headers.
     *
     * Returns 0/0 for a diff whose hunks were suppressed - use [computeDiff] when the counts must
     * hold for arbitrarily large changes.
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
        val result = computeDiff(originalContent, newContent, filePath)
        return ChangeSummary(
            addedLines = result.addedLines,
            removedLines = result.removedLines,
            unifiedDiff = result.diff,
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

    // ----- internal LCS / hunking implementation -----

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

    /**
     * Entries for the whole file: the [prefix] and [suffix] lines that both sides share verbatim
     * are emitted as context without ever entering the LCS table, which only covers the region
     * between them.
     */
    private fun buildDiffEntries(
        original: List<String>,
        updated: List<String>,
        prefix: Int,
        suffix: Int
    ): List<DiffEntry> {
        val result = mutableListOf<DiffEntry>()

        for (index in 0 until prefix) {
            result.add(DiffEntry(DiffEntryType.CONTEXT, original[index], index, index))
        }

        val m = original.size - prefix - suffix
        val n = updated.size - prefix - suffix
        val lcs = Array(m + 1) { IntArray(n + 1) }

        for (i in m - 1 downTo 0) {
            for (j in n - 1 downTo 0) {
                lcs[i][j] = if (original[prefix + i] == updated[prefix + j]) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }

        var i = 0
        var j = 0
        while (i < m && j < n) {
            when {
                original[prefix + i] == updated[prefix + j] -> {
                    result.add(DiffEntry(DiffEntryType.CONTEXT, original[prefix + i], prefix + i, prefix + j))
                    i++; j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> {
                    result.add(DiffEntry(DiffEntryType.DELETE, original[prefix + i], prefix + i, null))
                    i++
                }
                else -> {
                    result.add(DiffEntry(DiffEntryType.INSERT, updated[prefix + j], null, prefix + j))
                    j++
                }
            }
        }
        while (i < m) {
            result.add(DiffEntry(DiffEntryType.DELETE, original[prefix + i], prefix + i, null))
            i++
        }
        while (j < n) {
            result.add(DiffEntry(DiffEntryType.INSERT, updated[prefix + j], null, prefix + j))
            j++
        }

        for (k in 0 until suffix) {
            val oldIndex = original.size - suffix + k
            val newIndex = updated.size - suffix + k
            result.add(DiffEntry(DiffEntryType.CONTEXT, original[oldIndex], oldIndex, newIndex))
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
