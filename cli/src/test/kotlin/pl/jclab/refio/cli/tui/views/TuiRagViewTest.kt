package pl.jclab.refio.cli.tui.views

import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiRagFile
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.theme.ColorDepth
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuiRagViewTest {

    private val theme = Theme(ColorDepth.NONE)
    private val files = (1..30).map { TuiRagFile("src/main/F$it.kt", 2, 2, 1_500L) }

    private fun text(state: TuiState) = TuiRagView.renderToBuffer(state, 80, 20, theme, Glyphs.UNICODE).getLines().map(TuiRenderBuffer::stripAnsi)

    @Test
    fun `indexed files present never shows the empty-index message`() {
        val out = text(TuiState(ragIndexedFiles = files, ragEnabled = true))
        assertFalse(out.any { it.contains("no index") || it.contains("No indexed files") }, out.joinToString("\n"))
        assertTrue(out.first().contains("ready · 30 files indexed"))
    }

    @Test
    fun `index present but RAG disabled is said plainly`() {
        assertEquals(TuiRagView.IndexState.DISABLED, TuiRagView.indexState(TuiState(ragIndexedFiles = files, ragEnabled = false)))
        assertEquals("off", TuiRagView.badge(TuiState(ragIndexedFiles = files, ragEnabled = false)))
    }

    @Test
    fun `empty index without indexing tells how to start it`() {
        assertTrue(text(TuiState()).first().contains("no index yet: r starts indexing"))
    }

    @Test
    fun `table keeps the selected file in view`() {
        val out = text(TuiState(ragIndexedFiles = files, ragEnabled = true, ragSelectedFileIndex = 25))
        assertTrue(out.any { it.startsWith("▌ src/main/F26.kt") }, out.joinToString("\n"))
    }

    @Test
    fun `clearing the index asks first`() {
        val out = text(TuiState(ragIndexedFiles = files, ragEnabled = true, ragClearArmed = true))
        assertTrue(out.first().contains("c again clears the whole index"), out.joinToString("\n"))
    }

    @Test
    fun `sizes carry units`() {
        assertEquals("497B", TuiRagView.formatFileSize(497))
        assertEquals("107.1K", TuiRagView.formatFileSize(109_670))
    }
}
