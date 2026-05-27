package pl.jclab.refio.core.tools.implementations

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendMessageToolTest {

    private lateinit var eventBus: AgentEventBus
    private lateinit var tool: SendMessageTool

    @BeforeEach
    fun setup() {
        eventBus = AgentEventBus()
        tool = SendMessageTool(eventBus)
    }

    @Test
    fun `has correct metadata`() {
        assertEquals("send_message", tool.name)
        assertEquals(ToolMode.READ_ONLY, tool.mode)
        assertEquals(ToolCategory.SYSTEM, tool.category)
    }

    @Test
    fun `question type returns AWAITING_RESPONSE`() = runBlocking {
        val result = tool.execute(mapOf(
            "_agent_id" to "agent-1",
            "_task_id" to "task-1",
            "_parent_run_id" to "parent-1",
            "message" to "What format should I use?",
            "type" to "question"
        ))

        assertTrue(result.success)
        assertEquals("AWAITING_RESPONSE", result.metadata!!["type"])
        assertTrue(result.metadata!!.containsKey("requestId"))
        assertEquals("question", result.metadata!!["messageType"])
    }

    @Test
    fun `blocker type returns AWAITING_RESPONSE`() = runBlocking {
        val result = tool.execute(mapOf(
            "_agent_id" to "agent-1",
            "_task_id" to "task-1",
            "_parent_run_id" to "parent-1",
            "message" to "Cannot continue without API key",
            "type" to "blocker"
        ))

        assertTrue(result.success)
        assertEquals("AWAITING_RESPONSE", result.metadata!!["type"])
    }

    @Test
    fun `info type returns MESSAGE_SENT`() = runBlocking {
        val result = tool.execute(mapOf(
            "_agent_id" to "agent-1",
            "_task_id" to "task-1",
            "_parent_run_id" to "parent-1",
            "message" to "Found 5 relevant files",
            "type" to "info"
        ))

        assertTrue(result.success)
        assertEquals("MESSAGE_SENT", result.metadata!!["type"])
        assertTrue(result.output!!.contains("Found 5 relevant files"))
    }

    @Test
    fun `fails without agent_id`() = runBlocking {
        val result = tool.execute(mapOf(
            "message" to "test",
            "type" to "info"
        ))
        assertFalse(result.success)
    }

    @Test
    fun `fails without message`() = runBlocking {
        val result = tool.execute(mapOf(
            "_agent_id" to "agent-1",
            "type" to "question"
        ))
        assertFalse(result.success)
    }

    @Test
    fun `default target is parent`() = runBlocking {
        val result = tool.execute(mapOf(
            "_agent_id" to "agent-1",
            "_task_id" to "task-1",
            "_parent_run_id" to "parent-1",
            "message" to "test",
            "type" to "info"
        ))
        assertTrue(result.success)
        assertEquals("parent", result.metadata!!["target"])
    }

    @Test
    fun `parent without PARENT_RUN_ID fails fast`() = runBlocking {
        // Spec docs/0054 §3.3: 'to: parent' is resolved via PARENT_RUN_ID. Without it
        // the previous code emitted DataRequest(targetAgentId=null) and AgentTurnLoop
        // suspended for 5 minutes on a response that would never come.
        val result = tool.execute(mapOf(
            "_agent_id" to "agent-1",
            "_task_id" to "task-1",
            "message" to "test",
            "type" to "question"
        ))
        assertFalse(result.success)
        assertTrue((result.error ?: "").contains("PARENT_RUN_ID"))
    }

    @Test
    fun `unknown peer name with registry fails fast`() = runBlocking {
        // Phase 1 peer routing: rejecting unknown peer names prevents the same
        // suspend-and-time-out pitfall as the parent case above.
        val registry = pl.jclab.refio.core.agents.events.AgentInboxRegistry()
        val peerTool = pl.jclab.refio.core.tools.implementations.SendMessageTool(eventBus, registry)
        val result = peerTool.execute(mapOf(
            "_agent_id" to "agent-1",
            "_task_id" to "task-1",
            "_session_id" to "s1",
            "message" to "hi",
            "type" to "question",
            "to" to "ghost"
        ))
        assertFalse(result.success)
        assertTrue((result.error ?: "").contains("ghost"))
    }
}
