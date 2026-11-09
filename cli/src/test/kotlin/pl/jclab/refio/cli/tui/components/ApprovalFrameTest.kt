package pl.jclab.refio.cli.tui.components

import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiToolApprovalRequest
import pl.jclab.refio.cli.tui.theme.ColorDepth
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.core.services.turn.ProposedChange
import pl.jclab.refio.core.services.turn.ToolApprovalService
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalFrameTest {

    private val theme = Theme(ColorDepth.NONE)

    private fun text(state: TuiState, width: Int = 80, maxRows: Int = Int.MAX_VALUE) =
        ApprovalFrame.render(state, width, theme, Glyphs.UNICODE, maxRows).map(TuiRenderBuffer::stripAnsi)

    private fun edit(diff: String, old: String? = "x") = TuiToolApprovalRequest(
        "r1", "advance_code_editing", "Edit file", mapOf("path" to "src/main/A.kt"),
        ProposedChange("src/main/A.kt", old, "y", diff),
    )

    @Test
    fun `long unchanged runs fold so the change itself stays in view`() {
        // The core's format: a sign and a space before every line.
        val context = (1..10).joinToString("\n") { "  line $it" }
        val diff = "--- a/src/main/A.kt\n+++ b/src/main/A.kt\n@@ -1,11 +1,11 @@\n$context\n- old\n+ new\n"
        val out = text(TuiState(pendingToolApproval = edit(diff)))
        assertTrue(out.any { it.contains("8 lines folded") }, out.joinToString("\n"))
        // One space after the sign: the core's own space before the text is not doubled.
        assertTrue(out.any { it.contains("- old") }, out.joinToString("\n"))
        assertTrue(out.any { it.contains("+ new") })
        assertTrue(out.any { it.contains("+1") && it.contains("−1") && it.contains("edit") })
    }

    @Test
    fun `a diff longer than the frame says how to see the rest`() {
        val diff = "@@ -0,0 +1,30 @@\n" + (1..30).joinToString("\n") { "+ l$it" }
        val out = text(TuiState(pendingToolApproval = edit(diff, old = "")))
        assertTrue(out.any { it.contains("18 more lines · d open the full diff") }, out.joinToString("\n"))
        assertTrue(out.any { it.contains("new file") })
    }

    @Test
    fun `an existing file too large to carry its old content is an edit, not a new file`() {
        val out = text(TuiState(pendingToolApproval = edit("@@ -1 +1 @@\n- a\n+ b", old = null)))
        assertTrue(out.any { it.contains(" edit ") }, out.joinToString("\n"))
        assertTrue(out.none { it.contains("new file") })
    }

    @Test
    fun `a frame taller than the chat area shortens the diff and keeps the path in view`() {
        val diff = "@@ -0,0 +1,30 @@\n" + (1..30).joinToString("\n") { "+ l$it" }
        val out = text(TuiState(pendingToolApproval = edit(diff, old = "")), maxRows = 18)
        assertTrue(out.size <= 18, "${out.size} rows:\n" + out.joinToString("\n"))
        assertTrue(out.any { it.contains("src/main/A.kt") })
        assertTrue(out.any { it.contains("more lines · d open the full diff") })
    }

    @Test
    fun `a shell command is shown in full before it runs`() {
        val command = "./gradlew test \\\n  --tests '*Approval*' \\\n  && rm -rf build/tmp"
        val req = TuiToolApprovalRequest("r2", "run_terminal_command", "Run a command", mapOf("command" to command), null)
        val out = text(TuiState(pendingToolApproval = req))
        assertTrue(out.any { it.contains("&& rm -rf build/tmp") }, out.joinToString("\n"))
        assertEquals(listOf('y', 'a', 'd', 'n'), ApprovalFrame.choices(TuiState(pendingToolApproval = req)).map { it.key })
    }

    @Test
    fun `allow choice names the folder of the file`() {
        val choices = ApprovalFrame.choices(TuiState(pendingToolApproval = edit("@@ -1 +1 @@\n-a\n+b")))
        assertEquals(listOf('y', 'a', 'd', 'n'), choices.map { it.key })
        assertTrue(choices[1].label.contains("in src/main/"))
    }

    @Test
    fun `choices are dimmed with a hint while the prompt has text`() {
        val out = text(TuiState(pendingToolApproval = edit("@@ -1 +1 @@\n-a\n+b"), inputBuffer = "wait"))
        assertTrue(out.any { it.contains("send or clear it to choose") })
    }

    @Test
    fun `allowed rules read as tool and folder`() {
        val state = TuiState(trustRules = listOf(
            ToolApprovalService.TrustRuleView("advance_code_editing", pl.jclab.refio.cli.tui.state.FolderTrustRule.pattern("src/main/")),
            ToolApprovalService.TrustRuleView("run_terminal_command", null),
        ))
        val out = ApprovalFrame.allowedLines(state, 50, theme, Glyphs.UNICODE).map(TuiRenderBuffer::stripAnsi)
        assertTrue(out[0].contains("src/main/"), out.joinToString("\n"))
        assertTrue(out[1].contains("everywhere"))
    }
}
