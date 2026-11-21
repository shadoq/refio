package pl.jclab.refio.cli.tui.components

import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiScreen
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.state.TuiTab
import pl.jclab.refio.cli.tui.theme.ColorDepth
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusBarTest {

    private val theme = Theme(ColorDepth.TRUECOLOR)
    private val state = TuiState(
        mode = "PLAN", model = "ollama/qwen3.8:27b-mlx", contextUsedTokens = 16_700, contextMaxTokens = 32_768,
        sessionTokensIn = 21_700, sessionTokensOut = 154, totalCostUsd = 0.0
    )

    private fun plain(s: String) = TuiRenderBuffer.stripAnsi(s)

    @Test
    fun `local models never show a four decimal zero cost`() {
        assertEquals("local · \$0", StatusBar.formatCost(0.0, isLocal = true))
        assertEquals("\$0.0123", StatusBar.formatCost(0.0123, isLocal = false))
        assertEquals("\$0", StatusBar.formatCost(0.0, isLocal = false))
        assertEquals("-", StatusBar.formatCost(null, isLocal = true))
        assertTrue(StatusBar.isLocalModel("ollama/qwen3.5:9b"))
        assertTrue(StatusBar.isLocalModel("lmstudio/any"))
        assertFalse(StatusBar.isLocalModel("anthropic/claude"))
        assertFalse(StatusBar.isLocalModel(null))
    }

    @Test
    fun `wide bar shows every segment and is exactly as wide as the terminal`() {
        val bar = StatusBar.render(state, 200, theme, Glyphs.UNICODE)
        assertEquals(200, TuiRenderBuffer.visibleLength(bar))
        val text = plain(bar)
        listOf("PLAN", "qwen3.8:27b-mlx", "idle", "50%", "16.7K/32.8K", "↓21.7K ↑154", "0 req", "local · \$0", "effort off", "exec auto", "^Q quit")
            .forEach { assertTrue(text.contains(it), "missing '$it' in '$text'") }
    }

    @Test
    fun `narrow bar drops low-priority segments but keeps mode badge and key hints`() {
        val text = plain(StatusBar.render(state, 100, theme, Glyphs.UNICODE))
        assertTrue(text.contains("PLAN") && text.contains("Shift+Tab"), text)
        assertFalse(text.contains("effort"), "effort goes first: $text")
        assertFalse(text.contains("exec"), "exec goes second: $text")
        assertEquals(100, TuiRenderBuffer.visibleLength(StatusBar.render(state, 100, theme, Glyphs.UNICODE)))
        assertEquals(80, TuiRenderBuffer.visibleLength(StatusBar.render(state, 80, theme, Glyphs.UNICODE)))
    }

    @Test
    fun `key hints follow focus`() {
        assertEquals("Shift+Tab", StatusBar.hintsFor(state).first().first)
        assertEquals("Tab" to "prompt", StatusBar.hintsFor(state.copy(activeTab = TuiTab.STEPS, panelFocused = true)).first())
        assertEquals("↑↓" to "field", StatusBar.hintsFor(state.copy(screen = TuiScreen.SETTINGS)).first())
        assertEquals("^C" to "cancel", StatusBar.hintsFor(state.copy(isStreaming = true)).first())
    }

    @Test
    fun `settings show where you are instead of session numbers`() {
        val text = plain(StatusBar.render(state.copy(screen = TuiScreen.SETTINGS, settingsTab = 7), 140, theme, Glyphs.UNICODE))
        assertTrue(text.contains("SETTINGS") && text.contains("tools"), text)
        assertFalse(text.contains("req"), text)
    }

    @Test
    fun `gauge fills cells with a half block for the remainder`() {
        assertEquals("█░░░░░░░░░", StatusBar.gauge(13, Glyphs.UNICODE))
        assertEquals("█▌░░░░░░░░", StatusBar.gauge(15, Glyphs.UNICODE))
        assertEquals("██████████", StatusBar.gauge(100, Glyphs.UNICODE))
        assertEquals("░░░░░░░░░░", StatusBar.gauge(0, Glyphs.UNICODE))
    }
}
