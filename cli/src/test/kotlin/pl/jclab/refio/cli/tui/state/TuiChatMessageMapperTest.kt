package pl.jclab.refio.cli.tui.state

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.agents.events.AgentEvent
import kotlin.test.*

class TuiChatMessageMapperTest {

    @AfterEach
    fun cleanup() {
        TuiChatMessageMapper.reset()
    }

    @Test
    fun `should assign unique color indices to different agents`() {
        val idx1 = TuiChatMessageMapper.getAgentColorIndex("agent-1")
        val idx2 = TuiChatMessageMapper.getAgentColorIndex("agent-2")
        assertNotEquals(idx1, idx2)
    }

    @Test
    fun `should return same color index for same agent`() {
        val idx1 = TuiChatMessageMapper.getAgentColorIndex("agent-1")
        val idx2 = TuiChatMessageMapper.getAgentColorIndex("agent-1")
        assertEquals(idx1, idx2)
    }

    @Test
    fun `reset should clear assigned indices`() {
        TuiChatMessageMapper.getAgentColorIndex("agent-1")
        TuiChatMessageMapper.reset()
        val idx = TuiChatMessageMapper.getAgentColorIndex("agent-2")
        assertEquals(0, idx, "After reset, first assigned index should be 0")
    }

    @Test
    fun `mapEvent AgentStarted should produce AGENT_STARTED message`() {
        val event = AgentEvent.AgentStarted(
            id = "e1",
            sessionId = "s1",
            sourceAgentId = "a1",
            timestamp = 1000L,
            correlationId = "c1",
            agentName = "TestAgent",
            profile = null,
            task = "do stuff",
            model = "gpt-4",
            dependsOn = listOf("a0")
        )
        val msg = TuiChatMessageMapper.mapEvent(event)
        assertNotNull(msg)
        assertEquals(TuiMessageType.AGENT_STARTED, msg.messageType)
        assertTrue(msg.content.contains("TestAgent"))
        assertTrue(msg.content.contains("do stuff"))
        assertEquals("a1", msg.agentId)
    }

    @Test
    fun `mapEvent AgentCompleted should produce AGENT_COMPLETED message`() {
        val event = AgentEvent.AgentCompleted(
            id = "e2",
            sessionId = "s1",
            sourceAgentId = "a1",
            timestamp = 2000L,
            correlationId = "c1",
            summary = "Task done",
            artifacts = emptyList(),
            tokensUsed = 500,
            costUsd = 0.01,
            durationMs = 3000
        )
        val msg = TuiChatMessageMapper.mapEvent(event)
        assertNotNull(msg)
        assertEquals(TuiMessageType.AGENT_COMPLETED, msg.messageType)
        assertEquals("Task done", msg.content)
    }

    @Test
    fun `mapEvent AgentFailed should produce AGENT_FAILED message`() {
        val event = AgentEvent.AgentFailed(
            id = "e3",
            sessionId = "s1",
            sourceAgentId = "a1",
            timestamp = 3000L,
            correlationId = "c1",
            error = "timeout",
            recoverable = false
        )
        val msg = TuiChatMessageMapper.mapEvent(event)
        assertNotNull(msg)
        assertEquals(TuiMessageType.AGENT_FAILED, msg.messageType)
        assertTrue(msg.content.contains("timeout"))
    }

    @Test
    fun `mapEvent StreamChunk incomplete should return null`() {
        val event = AgentEvent.StreamChunk(
            id = "e4",
            sessionId = "s1",
            sourceAgentId = "a1",
            timestamp = 4000L,
            correlationId = "c1",
            delta = "partial",
            accumulated = "partial",
            isComplete = false
        )
        val msg = TuiChatMessageMapper.mapEvent(event)
        assertNull(msg)
    }

    @Test
    fun `mapEvent StreamChunk should return null (handled by WorkflowListener)`() {
        val event = AgentEvent.StreamChunk(
            id = "e5",
            sessionId = "s1",
            sourceAgentId = "a1",
            timestamp = 5000L,
            correlationId = "c1",
            delta = "",
            accumulated = "full response",
            isComplete = true
        )
        // StreamChunk events are handled by TuiWorkflowListener, not by the mapper
        val msg = TuiChatMessageMapper.mapEvent(event)
        assertNull(msg)
    }

    @Test
    fun `mapEvent ApprovalDecision should return null`() {
        val event = AgentEvent.ApprovalDecision(
            id = "e6",
            sessionId = "s1",
            sourceAgentId = "user",
            timestamp = 6000L,
            correlationId = "c1",
            approvalId = "ap1",
            approved = true,
            reason = null
        )
        val msg = TuiChatMessageMapper.mapEvent(event)
        assertNull(msg)
    }

    @Test
    fun `mapEvent ProgressUpdate should produce message with phase`() {
        val event = AgentEvent.ProgressUpdate(
            id = "e7",
            sessionId = "s1",
            sourceAgentId = "a1",
            timestamp = 7000L,
            correlationId = "c1",
            phase = "analyzing",
            message = "Reading files",
            progress = 0.5f
        )
        val msg = TuiChatMessageMapper.mapEvent(event)
        assertNotNull(msg)
        assertTrue(msg.content.contains("analyzing"))
        assertTrue(msg.content.contains("Reading files"))
    }
}
