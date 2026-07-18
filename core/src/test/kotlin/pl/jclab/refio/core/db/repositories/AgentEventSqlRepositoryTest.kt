package pl.jclab.refio.core.db.repositories

import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.Artifact
import pl.jclab.refio.testutil.TestDatabase
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentEventSqlRepositoryTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var repository: AgentEventSqlRepository

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        repository = AgentEventSqlRepository()
    }

    @AfterEach
    fun cleanup() {
        db.keepAlive.close()
    }

    private fun makeEvent(
        sessionId: String = "session-1",
        agentId: String = "agent-1"
    ): AgentEvent.AgentStarted = AgentEvent.AgentStarted(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        sourceAgentId = agentId,
        timestamp = System.currentTimeMillis(),
        correlationId = "corr-1",
        agentName = "test-agent",
        profile = null,
        task = "Test task",
        model = "test-model",
        dependsOn = emptyList()
    )

    @Test
    fun `should save and retrieve event by sessionId`() = runTest {
        val event = makeEvent()
        repository.save(event)

        val events = repository.findBySessionId("session-1")

        assertEquals(1, events.size)
        val retrieved = events[0] as AgentEvent.AgentStarted
        assertEquals(event.id, retrieved.id)
        assertEquals("test-agent", retrieved.agentName)
        assertEquals("Test task", retrieved.task)
    }

    @Test
    fun `should retrieve events by agentId`() = runTest {
        repository.save(makeEvent(agentId = "agent-A"))
        repository.save(makeEvent(agentId = "agent-B"))
        repository.save(makeEvent(agentId = "agent-A"))

        val eventsA = repository.findByAgentId("agent-A")
        val eventsB = repository.findByAgentId("agent-B")

        assertEquals(2, eventsA.size)
        assertEquals(1, eventsB.size)
    }

    @Test
    fun `should return empty list for unknown sessionId`() = runTest {
        val events = repository.findBySessionId("nonexistent")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `should persist different event types`() = runTest {
        val started = makeEvent()
        val completed = AgentEvent.AgentCompleted(
            id = UUID.randomUUID().toString(),
            sessionId = "session-1",
            sourceAgentId = "agent-1",
            timestamp = System.currentTimeMillis(),
            correlationId = "corr-1",
            summary = "Done",
            artifacts = listOf(Artifact("FILE_CREATED", "test.kt", path = "/src/test.kt")),
            tokensUsed = 1000L,
            costUsd = 0.05,
            durationMs = 5000L
        )
        val failed = AgentEvent.AgentFailed(
            id = UUID.randomUUID().toString(),
            sessionId = "session-1",
            sourceAgentId = "agent-1",
            timestamp = System.currentTimeMillis(),
            correlationId = "corr-1",
            error = "Something broke",
            recoverable = true
        )

        repository.save(started)
        repository.save(completed)
        repository.save(failed)

        val events = repository.findBySessionId("session-1")
        assertEquals(3, events.size)

        assertTrue(events[0] is AgentEvent.AgentStarted)
        assertTrue(events[1] is AgentEvent.AgentCompleted)
        assertTrue(events[2] is AgentEvent.AgentFailed)

        val completedEvent = events[1] as AgentEvent.AgentCompleted
        assertEquals("Done", completedEvent.summary)
        assertEquals(1000L, completedEvent.tokensUsed)
    }

    @Test
    fun `should persist approval events`() = runTest {
        val approval = AgentEvent.ApprovalRequired(
            id = UUID.randomUUID().toString(),
            sessionId = "session-1",
            sourceAgentId = "agent-1",
            timestamp = System.currentTimeMillis(),
            correlationId = "corr-1",
            action = "Write file",
            actionType = "FILE_WRITE",
            risk = "MEDIUM",
            details = mapOf("path" to "/src/main.kt")
        )

        repository.save(approval)

        val events = repository.findBySessionId("session-1")
        assertEquals(1, events.size)
        val retrieved = events[0] as AgentEvent.ApprovalRequired
        assertEquals("Write file", retrieved.action)
        assertEquals("MEDIUM", retrieved.risk)
        assertEquals(mapOf("path" to "/src/main.kt"), retrieved.details)
    }

    @Test
    fun `should order events by timestamp`() = runTest {
        val event1 = AgentEvent.AgentStarted(
            id = "id-1", sessionId = "session-1", sourceAgentId = "agent-1",
            timestamp = 1000L, correlationId = "corr-1",
            agentName = "first", profile = null, task = "T1", model = null, dependsOn = emptyList()
        )
        val event2 = AgentEvent.AgentStarted(
            id = "id-2", sessionId = "session-1", sourceAgentId = "agent-2",
            timestamp = 2000L, correlationId = "corr-1",
            agentName = "second", profile = null, task = "T2", model = null, dependsOn = emptyList()
        )

        // Save in reverse order
        repository.save(event2)
        repository.save(event1)

        val events = repository.findBySessionId("session-1")
        assertEquals(2, events.size)
        assertEquals("id-1", events[0].id) // Earlier timestamp first
        assertEquals("id-2", events[1].id)
    }

    @Test
    fun `limit keeps the newest events but preserves ascending replay order`() = runTest {
        (1..5).forEach { i ->
            repository.save(
                AgentEvent.AgentStarted(
                    id = "id-$i", sessionId = "session-1", sourceAgentId = "agent-1",
                    timestamp = i * 1000L, correlationId = "corr-1",
                    agentName = "a$i", profile = null, task = "T$i", model = null, dependsOn = emptyList()
                )
            )
        }

        val events = repository.findBySessionId("session-1", limit = 3)

        // Newest 3 survive the cap, replayed oldest-to-newest
        assertEquals(listOf("id-3", "id-4", "id-5"), events.map { it.id })
    }
}
