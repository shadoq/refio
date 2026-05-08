package pl.jclab.refio.core.services.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiffCompressorTest {

    @Test
    fun `text without diff fence is returned untouched`() {
        val input = "File created successfully: foo.html\nSize: 1234 bytes\nNo diff here"
        assertEquals(input, DiffCompressor.compress(input))
    }

    @Test
    fun `small diff under threshold is preserved verbatim`() {
        val small = buildDiff(
            header = listOf("--- a/foo.kt", "+++ b/foo.kt", "@@ -10,3 +10,3 @@"),
            // 10 changes — well under SMALL_DIFF_THRESHOLD (100)
            body = listOf(" fun a() {", "-    return 1", "+    return 2", " }")
        )
        val wrapped = "Edit summary\n```diff\n$small\n```\nTrailing prose"
        val out = DiffCompressor.compress(wrapped)
        assertEquals(wrapped, out, "small diff should pass through unchanged")
    }

    @Test
    fun `pure-create large diff keeps headers head and tail and emits recovery hint`() {
        val plusLines = (1..500).map { "+line $it" }
        val body = listOf(
            "--- a/snake.html",
            "+++ b/snake.html",
            "@@ -1,1 +1,500 @@"
        ) + plusLines
        val wrapped = "Created snake.html (+500/-0)\n```diff\n${body.joinToString("\n")}\n```"

        val out = DiffCompressor.compress(wrapped)

        assertTrue(out.contains("--- a/snake.html"), "file path header preserved")
        assertTrue(out.contains("+++ b/snake.html"))
        assertTrue(out.contains("@@ -1,1 +1,500 @@"), "hunk header preserved")
        assertTrue(out.contains("+line 1"), "first added line preserved (head preview)")
        assertTrue(out.contains("+line 500"), "last added line preserved (tail preview)")
        assertFalse(out.contains("+line 250"), "middle line elided")
        assertTrue(out.contains("memory(action=\"get_subtask_output\""), "recovery hint emitted")
        assertTrue(out.contains("elided"), "marker mentions elision")
        // No subtaskId passed — fall back to attribute-pointer placeholder.
        assertTrue(
            out.contains("subtask_id=\"<see subtaskId attribute above>\""),
            "without subtaskId arg the marker points at the surrounding tag attribute"
        )
    }

    @Test
    fun `pure-create embeds the literal subtaskId in the recovery hint when provided`() {
        val plusLines = (1..500).map { "+line $it" }
        val body = listOf(
            "--- a/snake.html",
            "+++ b/snake.html",
            "@@ -1,1 +1,500 @@"
        ) + plusLines
        val wrapped = "```diff\n${body.joinToString("\n")}\n```"
        val sid = "4d4c8e7f-e861-40cf-8213-a2f1a1a67c26"

        val out = DiffCompressor.compress(wrapped, subtaskId = sid)

        // Literal subtaskId in the marker — agent can copy-paste it straight into
        // memory(get_subtask_output) without scanning attributes on the parent tag.
        assertTrue(
            out.contains("subtask_id=\"$sid\""),
            "marker should embed the literal subtaskId; got: ${out.lines().firstOrNull { "elided" in it }}"
        )
        assertFalse(
            out.contains("<see subtaskId attribute above>"),
            "fallback placeholder must not appear when an id is supplied"
        )
    }

    @Test
    fun `pure-create stays small after compression`() {
        val plusLines = (1..1000).map { "+content for line $it that takes some bytes per row" }
        val body = listOf("--- a/big.html", "+++ b/big.html", "@@ -1,1 +1,1000 @@") + plusLines
        val wrapped = "```diff\n${body.joinToString("\n")}\n```"
        val originalSize = wrapped.length

        val out = DiffCompressor.compress(wrapped)

        // We don't promise an exact size, but a 1000-line pure-create diff must
        // shrink dramatically — at minimum below 20% of the original. This is the
        // whole point of the optimization: the wrap-up agent turn no longer pays
        // for the entire generated file in input tokens.
        assertTrue(out.length < originalSize / 5, "pure-create should shrink ≥5×, got ${out.length} vs $originalSize")
    }

    @Test
    fun `large mixed diff preserves all plus and minus lines but elides context`() {
        // 80 changes (40+/40-) interleaved with 5 context lines each = 480 lines.
        // Above SMALL_DIFF_THRESHOLD so compression kicks in.
        val body = StringBuilder()
        body.appendLine("--- a/big.kt")
        body.appendLine("+++ b/big.kt")
        body.appendLine("@@ -1,400 +1,400 @@")
        repeat(60) { hunk ->
            // Context block (long enough to trigger elision)
            repeat(5) { body.appendLine(" context line $hunk-$it") }
            body.appendLine("-removed line $hunk")
            body.appendLine("+added line $hunk")
        }
        val raw = body.toString().trimEnd('\n')
        val wrapped = "```diff\n$raw\n```"

        val out = DiffCompressor.compress(wrapped)

        // Every semantic change must survive — that's the contract of mixed mode.
        repeat(60) { hunk ->
            assertTrue(out.contains("-removed line $hunk"), "removed line $hunk preserved")
            assertTrue(out.contains("+added line $hunk"), "added line $hunk preserved")
        }
        // Context should be largely gone (we keep 1 per hunk run, drop 4 of 5).
        assertFalse(out.contains("context line 0-4"), "trailing context lines elided")
        assertTrue(out.contains("..."), "elision marker present")
        assertTrue(out.contains("semantic changes preserved"), "marker explains what was kept")
        // Net result must be smaller than input.
        assertTrue(out.length < wrapped.length, "compressed output must be smaller than input")
    }

    @Test
    fun `multiple diff fences in one text are each processed`() {
        // Build the text without trimIndent so the embedded multi-line +diffs
        // keep their column-0 alignment — trimIndent() looks at the FIRST injected
        // line for the common-prefix calculation and silently breaks indentation
        // for the rest, which would push real `+a1` lines off column 0.
        val plus1 = (1..120).joinToString("\n") { "+a$it" }
        val plus2 = (1..120).joinToString("\n") { "+b$it" }
        val text = buildString {
            appendLine("Tool result A:")
            appendLine("```diff")
            appendLine("--- a/one.html")
            appendLine("+++ b/one.html")
            appendLine("@@ -1,1 +1,120 @@")
            appendLine(plus1)
            appendLine("```")
            appendLine("Then tool result B:")
            appendLine("```diff")
            appendLine("--- a/two.html")
            appendLine("+++ b/two.html")
            appendLine("@@ -1,1 +1,120 @@")
            appendLine(plus2)
            appendLine("```")
            append("Done.")
        }

        val out = DiffCompressor.compress(text)

        assertTrue(out.contains("--- a/one.html"))
        assertTrue(out.contains("--- a/two.html"))
        // Both pure-create — both should be elided.
        assertTrue(out.split("elided").size >= 3, "both diffs got an elision marker")
        assertTrue(out.contains("+a1") && out.contains("+a120"), "first diff head/tail preserved")
        assertTrue(out.contains("+b1") && out.contains("+b120"), "second diff head/tail preserved")
    }

    private fun buildDiff(header: List<String>, body: List<String>): String =
        (header + body).joinToString("\n")
}
