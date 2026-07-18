package pl.jclab.refio.ui.components.chat.bubble

import com.intellij.openapi.project.Project
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MarkdownRenderingService prepares model output for the chat bubble. The business rules under
 * test: the model's internal reasoning (thinking tags) must never leak into the rendered bubble,
 * markdown tables must degrade to a readable list (JEditorPane's HTML tables are unusable), and
 * the editor width math must never go below a readable floor.
 */
class MarkdownRenderingServiceTest {

    private fun service() = MarkdownRenderingService(
        project = mockk<Project>(relaxed = true),
        formatMarkdownEnabledProvider = { true },
        onFilePathClicked = {}
    )

    @Nested
    inner class ThinkingTagStripping {

        @Test
        fun `closed thinking block is removed and the visible answer kept`() {
            val out = service().stripThinkingTags("<think>secret plan</think>The answer is 42.")
            assertEquals("The answer is 42.", out)
            assertFalse(out.contains("secret plan"))
        }

        @Test
        fun `thinking variant tag is also removed`() {
            val out = service().stripThinkingTags("<thinking>internal</thinking>Visible.")
            assertEquals("Visible.", out)
        }

        // A stream cut mid-reasoning leaves an unclosed tag; everything after it is still
        // internal reasoning and must not render as the assistant's answer.
        @Test
        fun `unclosed thinking tag removes the tail`() {
            val out = service().stripThinkingTags("Answer first. <think>then it never closed")
            assertEquals("Answer first.", out)
        }

        @Test
        fun `tag matching is case-insensitive`() {
            val out = service().stripThinkingTags("<THINK>x</THINK>ok")
            assertEquals("ok", out)
        }

        @Test
        fun `content without thinking tags passes through trimmed`() {
            assertEquals("plain text", service().stripThinkingTags("  plain text  "))
        }
    }

    @Nested
    inner class TableNormalization {

        @Test
        fun `header and separator rows become a numbered list keyed by header`() {
            val md = """
                | Name | Role |
                | --- | --- |
                | Ala | admin |
                | Ola | user |
            """.trimIndent()
            val out = service().normalizeMarkdownTablesForRendering(md)
            assertTrue(out.contains("1. **Name:** Ala | **Role:** admin"), "got: $out")
            assertTrue(out.contains("2. **Name:** Ola | **Role:** user"), "got: $out")
            assertFalse(out.contains("---"), "separator row must not survive: $out")
        }

        @Test
        fun `blank cells are dropped instead of rendering empty labels`() {
            val md = """
                | Name | Role |
                | --- | --- |
                | Ala |  |
            """.trimIndent()
            val out = service().normalizeMarkdownTablesForRendering(md)
            assertTrue(out.contains("1. **Name:** Ala"), "got: $out")
            assertFalse(out.contains("**Role:**"), "blank cell must be dropped: $out")
        }

        @Test
        fun `text without a table is returned unchanged`() {
            val md = "just a line\nand | a pipe without separator"
            assertEquals(md, service().normalizeMarkdownTablesForRendering(md))
        }

        @Test
        fun `prose around the table is preserved`() {
            val md = """
                Intro line.
                | A |
                | - |
                | 1 |
                Outro line.
            """.trimIndent()
            val out = service().normalizeMarkdownTablesForRendering(md)
            assertTrue(out.startsWith("Intro line."))
            assertTrue(out.endsWith("Outro line."))
        }
    }

    @Nested
    inner class EditorWidth {

        @Test
        fun `width subtracts bubble padding and right gutter`() {
            // 16 padding + 12 gutter = 28
            assertEquals(372, service().resolveMarkdownEditorWidth(400))
        }

        // A collapsed tool window must not produce a zero/negative editor width - the floor
        // keeps the pane readable instead of collapsing the layout.
        @Test
        fun `width never drops below the readable floor`() {
            assertEquals(160, service().resolveMarkdownEditorWidth(0))
            assertEquals(160, service().resolveMarkdownEditorWidth(100))
        }
    }
}
