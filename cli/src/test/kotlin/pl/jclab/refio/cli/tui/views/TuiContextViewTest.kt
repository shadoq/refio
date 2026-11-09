package pl.jclab.refio.cli.tui.views

import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiContextSection
import pl.jclab.refio.cli.tui.state.TuiState
import pl.jclab.refio.cli.tui.theme.ColorDepth
import pl.jclab.refio.cli.tui.theme.Glyphs
import pl.jclab.refio.cli.tui.theme.Theme
import pl.jclab.refio.core.llm.ContextWindowSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TuiContextViewTest {

    private val theme = Theme(ColorDepth.NONE)

    private fun text(state: TuiState, width: Int = 54, height: Int = 20) =
        TuiContextView.renderToBuffer(state, width, height, theme, Glyphs.UNICODE).getLines().map(TuiRenderBuffer::stripAnsi)

    private val sections = listOf(
        TuiContextSection("Conversation", "conversation", 9_800, 32_768),
        TuiContextSection("Project overview", "project", 2_100, 32_768),
        TuiContextSection("Tool descriptions", "tools", 4_800, 32_768),
    )

    @Test
    fun `window comes from the resolved state, not from the sections`() {
        val state = TuiState(contextSections = sections.map { it.copy(tokensMax = 128_000) }, contextUsedTokens = 16_700, contextMaxTokens = 32_768)
        assertTrue(text(state).first().startsWith("16.7K / 32.8K tokens (50%)"), text(state).first())
    }

    @Test
    fun `gauge has three groups with one row each`() {
        val out = text(TuiState(contextSections = sections, contextUsedTokens = 16_700, contextMaxTokens = 32_768))
        assertEquals(1, out.count { it.contains("conversation") && it.contains("9.8K") }, out.joinToString("\n"))
        assertEquals(1, out.count { it.contains("project analysis") && it.contains("2.1K") })
        assertEquals(1, out.count { it.contains("overhead") && it.contains("4.8K") })
    }

    @Test
    fun `ollama default window is called out while num_ctx is not set`() {
        val state = TuiState(model = "ollama/qwen3:8b", contextMaxTokens = 32_768, contextWindowSource = ContextWindowSource.PROVIDER_DEFAULT)
        assertTrue(TuiContextView.windowWarning(state)!!.startsWith("num_ctx not set: Ollama default 32K in use"))
        assertTrue(text(state).any { it.contains("num_ctx not set") })
    }

    @Test
    fun `no warning once the size is set explicitly`() {
        assertNull(TuiContextView.windowWarning(TuiState(model = "ollama/qwen3:8b", contextMaxTokens = 65_536, contextWindowSource = ContextWindowSource.PROVIDER_EXPLICIT)))
    }

    @Test
    fun `unknown window is said plainly instead of a made-up number`() {
        assertTrue(text(TuiState()).first().contains("window not known yet"))
    }
}
