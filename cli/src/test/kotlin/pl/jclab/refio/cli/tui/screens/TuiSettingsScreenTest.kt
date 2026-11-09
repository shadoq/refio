package pl.jclab.refio.cli.tui.screens

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.SettingsNotice
import pl.jclab.refio.cli.tui.state.TuiRagFile
import pl.jclab.refio.cli.tui.state.TuiScreen
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiViewModel
import pl.jclab.refio.cli.tui.theme.ColorDepth
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuiSettingsScreenTest {

    private val theme = Theme(ColorDepth.NONE)
    private val viewModel = mockk<TuiViewModel>(relaxed = true).also {
        every { it.getConfigSection(any()) } returns emptyMap()
        every { it.getConfigSection("rag") } returns mapOf("enabled" to "false")
        TuiSettingsScreen.setViewModel(it)
    }

    @AfterEach
    fun detach() {
        TuiSettingsScreen.setViewModel(mockk(relaxed = true))
    }

    private fun text(state: TuiState, width: Int, height: Int = 40) =
        TuiSettingsScreen.renderToLines(state, width, height, theme, Glyphs.UNICODE).map(TuiRenderBuffer::stripAnsi)

    @Test
    fun `every tab fills the area exactly at every layout width`() {
        for (tab in 0..10) for (width in listOf(80, 140, 200)) {
            val lines = TuiSettingsScreen.renderToLines(TuiState(screen = TuiScreen.SETTINGS, settingsTab = tab), width, 30, theme, Glyphs.UNICODE)
            assertEquals(30, lines.size)
            lines.forEach { assertEquals(width, TuiRenderBuffer.visibleLength(it), "tab $tab at $width: '$it'") }
        }
    }

    @Test
    fun `each group is its own frame and the section list sits on the left`() {
        val out = text(TuiState(screen = TuiScreen.SETTINGS, settingsTab = 0), 140)
        assertTrue(out.first().startsWith("╭─ SETTINGS"), out.joinToString("\n"))
        assertTrue(out.any { it.contains("╭─ DISPLAY") } && out.any { it.contains("╭─ EXECUTION") }, out.joinToString("\n"))
        assertTrue(out.any { it.contains("[ ] Markdown rendering") })
    }

    @Test
    fun `a value left at its default says so without brackets`() {
        val out = text(TuiState(screen = TuiScreen.SETTINGS, settingsTab = 4), 200)
        assertTrue(out.any { it.contains("Similarity threshold") && it.contains("0.5  default") }, out.joinToString("\n"))
        assertFalse(out.any { it.contains("(default)") })
    }

    @Test
    fun `changes are never marked as modified because they are saved at once`() {
        val out = text(TuiState(screen = TuiScreen.SETTINGS, settingsNotice = SettingsNotice("saved 15:01", error = false)), 200)
        assertTrue(out.any { it.contains("saved 15:01") })
        assertFalse(out.any { it.contains("modified") })
    }

    @Test
    fun `index present with RAG off is called out above the settings`() {
        val files = (1..3).map { TuiRagFile("src/F$it.kt", 1, 1, 10) }
        val out = text(TuiState(screen = TuiScreen.SETTINGS, settingsTab = 4, ragIndexedFiles = files, ragEnabled = false), 200)
        assertTrue(out.any { it.contains("INDEX READY, RAG OFF") }, out.joinToString("\n"))
        assertTrue(out.any { it.contains("3 files are indexed, but RAG is disabled") })
        assertTrue(out.any { it.contains("src/F1.kt") })
    }

    @Test
    fun `unconfigured provider shows only the field that configures it`() {
        val out = text(TuiState(screen = TuiScreen.SETTINGS, settingsTab = 1), 200, 60)
        assertTrue(out.any { it.contains("○ LM STUDIO") }, out.joinToString("\n"))
        assertTrue(out.any { it.contains("Base URL") })
        assertFalse(out.any { it.contains("Raw request") }, "Custom OpenAI details stay hidden until its base URL is set")
    }

    @Test
    fun `narrow screen lists the sections on one line`() {
        val out = text(TuiState(screen = TuiScreen.SETTINGS, settingsTab = 7), 80)
        assertTrue(out.first().contains("8 Tools"), out.first())
    }

    @Test
    fun `out of range tab falls back to the first one`() {
        assertEquals(30, TuiSettingsScreen.renderToLines(TuiState(screen = TuiScreen.SETTINGS, settingsTab = 99), 120, 30, theme, Glyphs.UNICODE).size)
    }
}
