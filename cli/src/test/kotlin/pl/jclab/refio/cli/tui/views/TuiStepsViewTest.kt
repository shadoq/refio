package pl.jclab.refio.cli.tui.views

import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.subtaskFixture
import pl.jclab.refio.cli.tui.theme.ColorDepth
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuiStepsViewTest {

    private val theme = Theme(ColorDepth.NONE)

    private fun text(state: TuiState, width: Int = 48, height: Int = 20): List<String> =
        TuiStepsView.renderToBuffer(state, width, height, theme, Glyphs.UNICODE).getLines().map(TuiRenderBuffer::stripAnsi)

    @Test
    fun `step finished by core counts as done`() {
        val state = TuiState(
            subtasks = listOf(
                subtaskFixture("s1", "Read README", status = "SUCCESS", kind = "read_file", paramsJson = """{"path":"README.md"}"""),
                subtaskFixture("s2", "List files", status = "SUCCESS", kind = "list_dir"),
                subtaskFixture("s3", "Edit build", status = "RUNNING", kind = "code_editing"),
            )
        )
        val lines = text(state)
        assertTrue(lines.first().startsWith("2/3 done"), lines.joinToString("\n"))
        val readRow = lines.first { it.contains("README.md") }
        assertTrue(readRow.contains("✓"), "a step the core marked SUCCESS shows as done: $readRow")
        assertFalse(lines.any { it.contains("?") }, "no unknown-status marks: $lines")
    }

    @Test
    fun `actions wrap inside the column instead of being cut off`() {
        val state = TuiState(subtasks = listOf(subtaskFixture("s1", "Read", status = "PENDING", kind = "read_file")))
        val lines = text(state, width = 46)
        val actions = lines.filter { it.contains("[") }
        assertEquals(2, actions.size, "two action lines in a 46-column panel: $actions")
        assertTrue(actions.joinToString(" ").contains("[C]ancel all"), "the last action is still there: $actions")
        assertTrue(lines.all { TuiRenderBuffer.visibleLength(it) <= 46 }, "nothing wider than the panel")
    }

    @Test
    fun `failed step shows its error in the details`() {
        val state = TuiState(subtasks = listOf(subtaskFixture("s1", "Write file", status = "FAILED", kind = "create_new_file", errorMessage = "disk full")))
        val lines = text(state)
        assertTrue(lines.any { it.contains("✕") }, "failed mark: $lines")
        assertTrue(lines.any { it.contains("disk full") }, "error of the selected step: $lines")
    }

    @Test
    fun `no steps says where steps come from`() {
        assertTrue(text(TuiState()).first().contains("PLAN and AGENT"))
    }
}
