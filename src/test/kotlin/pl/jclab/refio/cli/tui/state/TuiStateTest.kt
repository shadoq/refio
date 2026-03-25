package pl.jclab.refio.cli.tui.state

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuiStateTest {

    @Test
    fun `default state should have sensible defaults`() {
        val state = TuiState()
        assertEquals(TuiScreen.MAIN, state.screen)
        assertEquals(TuiTab.CHAT, state.activeTab)
        assertTrue(state.messages.isEmpty())
        assertFalse(state.isStreaming)
        assertTrue(state.agents.isEmpty())
        assertEquals("CHAT", state.mode)
        assertEquals("", state.inputBuffer)
        assertEquals(0.0, state.totalCostUsd)
        assertEquals(0L, state.totalTokens)
    }

    @Test
    fun `TuiTab should have 7 entries`() {
        assertEquals(7, TuiTab.entries.size)
    }

    @Test
    fun `TuiTab labels should be non-empty`() {
        for (tab in TuiTab.entries) {
            assertTrue(tab.label.isNotBlank())
        }
    }

    @Test
    fun `TuiScreen should have 3 entries`() {
        assertEquals(3, TuiScreen.entries.size)
    }

    @Test
    fun `TuiMessageType should have 7 entries`() {
        assertEquals(7, TuiMessageType.entries.size)
    }

    @Test
    fun `TuiChatMessage should support agent metadata`() {
        val msg = TuiChatMessage(
            id = "1",
            timestamp = 1000L,
            role = "agent_event",
            content = "test",
            agentId = "a1",
            agentName = "TestAgent",
            agentColorIndex = 3,
            messageType = TuiMessageType.AGENT_STARTED
        )
        assertEquals("a1", msg.agentId)
        assertEquals("TestAgent", msg.agentName)
        assertEquals(3, msg.agentColorIndex)
    }

    @Test
    fun `TuiDebugInfo defaults should be empty`() {
        val info = TuiDebugInfo()
        assertEquals("", info.sessionId)
        assertEquals("CHAT", info.mode)
        assertEquals("IDLE", info.status)
        assertFalse(info.connected)
    }

    @Test
    fun `TuiAgentState should track all fields`() {
        val agent = TuiAgentState(
            id = "a1",
            name = "TestAgent",
            status = "RUNNING",
            colorIndex = 0,
            currentPhase = "analyzing",
            dependsOn = listOf("a0"),
            tokensUsed = 500,
            costUsd = 0.01
        )
        assertEquals("RUNNING", agent.status)
        assertEquals("analyzing", agent.currentPhase)
        assertEquals(listOf("a0"), agent.dependsOn)
    }
}
