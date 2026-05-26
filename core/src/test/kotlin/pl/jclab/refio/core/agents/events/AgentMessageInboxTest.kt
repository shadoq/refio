package pl.jclab.refio.core.agents.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class AgentMessageInboxTest {

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun req(
        sessionId: String,
        from: String,
        to: String?,
        id: String = UUID.randomUUID().toString(),
    ) = AgentEvent.DataRequest(
        id = id,
        sessionId = sessionId,
        sourceAgentId = from,
        timestamp = 0L,
        correlationId = id,
        targetAgentId = to,
        query = "q",
        context = mapOf("type" to "question")
    )

    @Test
    fun `captures only requests targeted at the owning agent`() = runBlocking {
        val bus = AgentEventBus()
        val scope = newScope()
        val inboxB = AgentMessageInbox("B", "s1", bus, scope)
        try {
            bus.emit(req("s1", from = "A", to = "B"))
            bus.emit(req("s1", from = "A", to = "B"))
            bus.emit(req("s1", from = "A", to = "B"))
            bus.emit(req("s1", from = "B", to = "A"))
            bus.emit(req("s1", from = "C", to = "A"))
            yield()
            assertEquals(3, inboxB.snapshotPending().size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `drops a request from pending once a matching DataResponse is observed`() = runBlocking {
        val bus = AgentEventBus()
        val scope = newScope()
        val inbox = AgentMessageInbox("B", "s1", bus, scope)
        try {
            val r1 = req("s1", from = "A", to = "B")
            val r2 = req("s1", from = "A", to = "B")
            bus.emit(r1)
            bus.emit(r2)
            yield()
            assertEquals(2, inbox.snapshotPending().size)

            bus.emit(
                AgentEvent.DataResponse(
                    id = UUID.randomUUID().toString(),
                    sessionId = "s1",
                    sourceAgentId = "B",
                    timestamp = 0L,
                    correlationId = r1.correlationId,
                    targetAgentId = "A",
                    requestId = r1.id,
                    response = "ok"
                )
            )
            yield()

            val remaining = inbox.snapshotPending()
            assertEquals(1, remaining.size)
            assertEquals(r2.id, remaining.single().id)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `ignores requests from a different session`() = runBlocking {
        val bus = AgentEventBus()
        val scope = newScope()
        val inbox = AgentMessageInbox("B", "s1", bus, scope)
        try {
            bus.emit(req("s1", from = "A", to = "B"))
            bus.emit(req("s2", from = "A", to = "B"))
            yield()
            assertEquals(1, inbox.snapshotPending().size)
            assertTrue(inbox.snapshotPending().all { it.sessionId == "s1" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `markAnswered removes the request`() = runBlocking {
        val bus = AgentEventBus()
        val scope = newScope()
        val inbox = AgentMessageInbox("B", "s1", bus, scope)
        try {
            val r = req("s1", from = "A", to = "B")
            bus.emit(r)
            yield()
            assertEquals(1, inbox.snapshotPending().size)

            inbox.markAnswered(r.id)
            assertTrue(inbox.snapshotPending().isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
