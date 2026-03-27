package pl.jclab.refio.cli.tui.state

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests session state management in TuiState (pure state tests).
 * Router-dependent methods are tested via integration tests.
 */
class TuiViewModelSessionTest {

    @Test
    fun `TuiSessionEntry should have default pinned false`() {
        val session = TuiSessionEntry(
            id = "s1", name = "Test", mode = "CHAT", status = "SUCCESS",
            tokensIn = 100, tokensOut = 50, costUsd = 0.01,
            createdAt = 1000L, updatedAt = 2000L
        )
        assertFalse(session.pinned)
    }

    @Test
    fun `TuiState activeSessionId should default to null`() {
        val state = TuiState()
        assertNull(state.activeSessionId)
    }

    @Test
    fun `TuiState selectedHistoryIndex should default to 0`() {
        val state = TuiState()
        assertEquals(0, state.selectedHistoryIndex)
    }

    @Test
    fun `TuiState historyFilter should default to all`() {
        val state = TuiState()
        assertEquals("*", state.historyFilter)
    }

    @Test
    fun `session filtering by mode should work`() {
        val sessions = listOf(
            TuiSessionEntry("id1", "S1", "CHAT", "SUCCESS", 0, 0, 0.0, 0, 0),
            TuiSessionEntry("id2", "S2", "AGENT", "SUCCESS", 0, 0, 0.0, 0, 0),
            TuiSessionEntry("id3", "S3", "CHAT", "SUCCESS", 0, 0, 0.0, 0, 0),
            TuiSessionEntry("id4", "S4", "PLAN", "SUCCESS", 0, 0, 0.0, 0, 0)
        )
        val chatOnly = sessions.filter { it.mode == "CHAT" }
        assertEquals(2, chatOnly.size)

        val agentOnly = sessions.filter { it.mode == "AGENT" }
        assertEquals(1, agentOnly.size)
    }

    @Test
    fun `session sorting by updatedAt should work`() {
        val sessions = listOf(
            TuiSessionEntry("id1", "Old", "CHAT", "SUCCESS", 0, 0, 0.0, 0, 1000L),
            TuiSessionEntry("id2", "New", "CHAT", "SUCCESS", 0, 0, 0.0, 0, 3000L),
            TuiSessionEntry("id3", "Mid", "CHAT", "SUCCESS", 0, 0, 0.0, 0, 2000L)
        )
        val sorted = sessions.sortedByDescending { it.updatedAt }
        assertEquals("New", sorted[0].name)
        assertEquals("Mid", sorted[1].name)
        assertEquals("Old", sorted[2].name)
    }

    @Test
    fun `TuiState mode should default to CHAT`() {
        val state = TuiState()
        assertEquals("CHAT", state.mode)
    }

    @Test
    fun `TuiState executionMode should default to AUTO`() {
        val state = TuiState()
        assertEquals("AUTO", state.executionMode)
    }

    @Test
    fun `TuiDebugInfo should have empty defaults`() {
        val debug = TuiDebugInfo()
        assertEquals("", debug.sessionId)
        assertEquals("CHAT", debug.mode)
        assertFalse(debug.connected)
    }
}
