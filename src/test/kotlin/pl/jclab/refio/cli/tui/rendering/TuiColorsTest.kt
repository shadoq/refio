package pl.jclab.refio.cli.tui.rendering

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TuiColorsTest {

    @Test
    fun `agent colors should have 8 entries`() {
        assertEquals(8, TuiColors.agentColors.size)
    }

    @Test
    fun `forAgent should cycle through colors`() {
        val color0 = TuiColors.forAgent(0)
        val color8 = TuiColors.forAgent(8)
        assertEquals(color0, color8, "Index 8 should wrap to same color as index 0")
    }

    @Test
    fun `forAgent should return different colors for different indices`() {
        val colors = (0 until 8).map { TuiColors.forAgent(it) }
        assertEquals(8, colors.toSet().size, "All 8 agent colors should be unique")
    }

    @Test
    fun `status colors should be distinct`() {
        val statusColors = setOf(
            TuiColors.statusNew,
            TuiColors.statusPending,
            TuiColors.statusRunning,
            TuiColors.statusSuccess,
            TuiColors.statusFailed
        )
        assertEquals(5, statusColors.size, "All 5 status colors should be unique")
    }

    @Test
    fun `log level colors should be defined`() {
        assertNotNull(TuiColors.logDebug)
        assertNotNull(TuiColors.logInfo)
        assertNotNull(TuiColors.logWarn)
        assertNotNull(TuiColors.logError)
    }

    @Test
    fun `context colors should be defined`() {
        assertNotNull(TuiColors.contextProject)
        assertNotNull(TuiColors.contextUser)
        assertNotNull(TuiColors.contextRag)
        assertNotNull(TuiColors.contextConversation)
        assertNotNull(TuiColors.contextTools)
    }

    @Test
    fun `UI element styles should be defined`() {
        assertNotNull(TuiColors.tabActive)
        assertNotNull(TuiColors.tabInactive)
        assertNotNull(TuiColors.border)
        assertNotNull(TuiColors.accent)
    }
}
