package pl.jclab.refio.core.tools

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Diff generation contract: it must stay cheap enough to run on any file that passes the write
 * tools' size limit, and it must never report "nothing changed" for a change that did reach disk.
 */
class DiffUtilsTest {

    @Test
    fun `byte-identical content is the only real no-op`() {
        val content = "alpha\nbeta\n"

        val summary = DiffUtils.buildChangeSummary(content, content, "a.kt")

        assertTrue(summary.noop)
        assertEquals(0, summary.addedLines)
        assertEquals(0, summary.removedLines)
    }

    @Test
    fun `whole-file line-ending rewrite is reported as a change, not a no-op`() {
        // A CRLF file rewritten as LF is a full-file change in git, so the tool must not tell the
        // agent "No changes applied" - the line-based diff sees nothing because lines() drops \r.
        val crlf = "alpha\r\nbeta\r\ngamma\r\n"
        val lf = "alpha\nbeta\ngamma\n"

        val summary = DiffUtils.buildChangeSummary(crlf, lf, "a.kt")

        assertFalse(summary.noop, "EOL-only rewrite changes every byte of every line ending on disk")
    }

    @Test
    fun `single-line edit inside a very large file still produces a precise diff`() {
        // 40k lines: a full LCS matrix would be 40k*40k ints (~6.4 GB). Trimming the common
        // prefix/suffix must reduce this to the one changed line.
        val lineCount = 40_000
        val original = (1..lineCount).joinToString("\n") { "line $it" }
        val updated = original.replace("line 20000", "line 20000 CHANGED")

        val result = DiffUtils.computeDiff(original, updated, "big.txt")

        assertFalse(result.suppressed, "a one-line change must not degrade the diff")
        assertEquals(1, result.addedLines)
        assertEquals(1, result.removedLines)
        assertTrue(result.diff.contains("+ line 20000 CHANGED"))
        assertTrue(result.diff.contains("- line 20000"))
    }

    @Test
    fun `oversized changed region degrades to a summary instead of allocating a full LCS matrix`() {
        // 3000 x 3000 fully different lines = 9M matrix cells. Producing that diff costs ~36 MB
        // of heap and yields an unreadable 6000-line diff, so it must be degraded instead.
        val original = (1..3000).joinToString("\n") { "old line $it" }
        val updated = (1..3000).joinToString("\n") { "new line $it" }

        val result = DiffUtils.computeDiff(original, updated, "generated.json")

        assertTrue(result.suppressed, "changed region above the cell budget must be degraded")
        assertTrue(result.diff.contains(DiffUtils.SUPPRESSED_DIFF_MARKER))
        assertEquals(3000, result.addedLines)
        assertEquals(3000, result.removedLines)
        assertFalse(result.diff.lines().size > 20, "degraded diff must stay short, was ${result.diff.lines().size} lines")
    }

    @Test
    fun `degraded diff still reports the change in the summary`() {
        val original = (1..3000).joinToString("\n") { "old line $it" }
        val updated = (1..3000).joinToString("\n") { "new line $it" }

        val summary = DiffUtils.buildChangeSummary(original, updated, "generated.json")

        assertFalse(summary.noop, "a degraded diff must never look like a no-op to the agent")
        assertEquals(3000, summary.addedLines)
        assertEquals(3000, summary.removedLines)
    }
}
