package pl.jclab.refio.core.tools.implementations

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.agents.events.AgentInboxRegistry
import pl.jclab.refio.core.agents.events.AgentMessageInbox

class AnswerMessageToolTest {

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun `rejects call without AGENT_NAME`() = runBlocking {
        val bus = AgentEventBus()
        val registry = AgentInboxRegistry()
        val scope = newScope()
        val inbox = AgentMessageInbox("B", "s1", bus, scope)
        registry.register(inbox)
        val tool = AnswerMessageTool(bus, registry)
        try {
            val r = tool.execute(mapOf(
                "_session_id" to "s1",
                "requestId" to "x",
                "response" to "y"
            ))
            assertFalse(r.success)
            assertTrue((r.error ?: "").contains("AGENT_NAME"))
            assertNotNull(registry.find("s1", "B"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `rejects call without SESSION_ID`() = runBlocking {
        val bus = AgentEventBus()
        val registry = AgentInboxRegistry()
        val scope = newScope()
        AgentMessageInbox("B", "s1", bus, scope).also { registry.register(it) }
        val tool = AnswerMessageTool(bus, registry)
        try {
            val r = tool.execute(mapOf(
                "_agent_name" to "B",
                "requestId" to "x",
                "response" to "y"
            ))
            assertFalse(r.success)
            assertTrue((r.error ?: "").contains("SESSION_ID"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `rejects unknown requestId without emitting`() = runBlocking {
        val bus = AgentEventBus()
        val registry = AgentInboxRegistry()
        val scope = newScope()
        AgentMessageInbox("B", "s1", bus, scope).also { registry.register(it) }
        val tool = AnswerMessageTool(bus, registry)

        var emittedResponses = 0
        val collector = scope.launch {
            bus.events.collect { if (it is AgentEvent.DataResponse) emittedResponses++ }
        }
        try {
            val r = tool.execute(mapOf(
                "_agent_name" to "B",
                "_session_id" to "s1",
                "requestId" to "ghost",
                "response" to "hi"
            ))
            assertFalse(r.success)
            assertTrue((r.error ?: "").contains("No pending request"))
        } finally {
            collector.cancel()
            scope.cancel()
        }
        assertEquals(0, emittedResponses)
    }

    @Test
    fun `happy path emits DataResponse and clears the inbox entry`() = runBlocking {
        val bus = AgentEventBus()
        val registry = AgentInboxRegistry()
        val scope = newScope()
        val inbox = AgentMessageInbox("B", "s1", bus, scope)
        registry.register(inbox)
        val tool = AnswerMessageTool(bus, registry)

        try {
            val req = AgentEvent.DataRequest(
                id = "req-1",
                sessionId = "s1",
                sourceAgentId = "A",
                timestamp = 0L,
                correlationId = "corr-1",
                targetAgentId = "B",
                query = "ping?",
                context = mapOf("type" to "question")
            )
            bus.emit(req)
            // Yield until the inbox collector picks the request up.
            repeat(20) {
                if (inbox.snapshotPending().any { it.id == "req-1" }) return@repeat
                yield()
            }
            assertTrue(inbox.snapshotPending().any { it.id == "req-1" }, "inbox did not pick up the request")

            val collected = async(start = CoroutineStart.UNDISPATCHED) {
                bus.events.first { it is AgentEvent.DataResponse && it.requestId == "req-1" } as AgentEvent.DataResponse
            }

            val r = tool.execute(mapOf(
                "_agent_name" to "B",
                "_session_id" to "s1",
                "requestId" to "req-1",
                "response" to "pong"
            ))
            assertTrue(r.success)

            val resp = collected.await()
            assertEquals("A", resp.targetAgentId)
            assertEquals("B", resp.sourceAgentId)
            assertEquals("pong", resp.response)

            assertTrue(inbox.snapshotPending().none { it.id == "req-1" })
        } finally {
            scope.cancel()
        }
    }
}
