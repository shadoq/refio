package pl.jclab.refio.core.agents.events

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentEventBusTest {

    private fun makeEvent(
        sessionId: String = "s1",
        sourceAgentId: String = "a1",
        type: String = "progress"
    ): AgentEvent = AgentEvent.ProgressUpdate(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        sourceAgentId = sourceAgentId,
        timestamp = System.currentTimeMillis(),
        correlationId = "c1",
        phase = "testing",
        message = "test message",
        progress = null
    )

    private fun makeStarted(
        sessionId: String = "s1",
        agentId: String = "a1",
        agentName: String = "TestAgent"
    ): AgentEvent.AgentStarted = AgentEvent.AgentStarted(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        sourceAgentId = agentId,
        timestamp = System.currentTimeMillis(),
        correlationId = "c1",
        agentName = agentName,
        profile = null,
        task = "test task",
        model = "test-model",
        dependsOn = emptyList()
    )

    private fun makeCompleted(
        sessionId: String = "s1",
        agentId: String = "a1"
    ): AgentEvent.AgentCompleted = AgentEvent.AgentCompleted(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        sourceAgentId = agentId,
        timestamp = System.currentTimeMillis(),
        correlationId = "c1",
        summary = "done",
        artifacts = emptyList(),
        tokensUsed = 100,
        costUsd = 0.01,
        durationMs = 5000
    )

    @Nested
    inner class BasicEmitAndSubscribe {

        @Test
        fun `should emit and receive events`() = runTest {
            val bus = AgentEventBus()
            val collected = mutableListOf<AgentEvent>()

            val job = launch { bus.events.take(1).toList(collected) }
            bus.emit(makeEvent())
            job.join()

            assertEquals(1, collected.size)
        }

        @Test
        fun `should support multiple subscribers`() = runTest {
            val bus = AgentEventBus()
            val list1 = mutableListOf<AgentEvent>()
            val list2 = mutableListOf<AgentEvent>()

            val job1 = launch { bus.events.take(1).toList(list1) }
            val job2 = launch { bus.events.take(1).toList(list2) }
            bus.emit(makeEvent())
            job1.join()
            job2.join()

            assertEquals(1, list1.size)
            assertEquals(1, list2.size)
        }
    }

    @Nested
    inner class FilteredSubscriptions {

        @Test
        fun `sessionEvents should filter by session`() = runTest {
            val bus = AgentEventBus()
            val collected = mutableListOf<AgentEvent>()

            val job = launch { bus.sessionEvents("s1").take(1).toList(collected) }
            bus.emit(makeEvent(sessionId = "s2")) // wrong session
            bus.emit(makeEvent(sessionId = "s1")) // right session
            job.join()

            assertEquals(1, collected.size)
            assertEquals("s1", collected[0].sessionId)
        }

        @Test
        fun `agentEvents should filter by agent`() = runTest {
            val bus = AgentEventBus()
            val collected = mutableListOf<AgentEvent>()

            val job = launch { bus.agentEvents("a1").take(1).toList(collected) }
            bus.emit(makeEvent(sourceAgentId = "a2")) // wrong agent
            bus.emit(makeEvent(sourceAgentId = "a1")) // right agent
            job.join()

            assertEquals(1, collected.size)
            assertEquals("a1", collected[0].sourceAgentId)
        }

        @Test
        fun `lifecycleEvents should only return lifecycle types`() = runTest {
            val bus = AgentEventBus()
            val collected = mutableListOf<AgentEvent>()

            val job = launch { bus.lifecycleEvents("s1").take(2).toList(collected) }
            bus.emit(makeEvent(sessionId = "s1")) // ProgressUpdate — not lifecycle
            bus.emit(makeStarted(sessionId = "s1"))
            bus.emit(makeCompleted(sessionId = "s1"))
            job.join()

            assertEquals(2, collected.size)
            assertIs<AgentEvent.AgentStarted>(collected[0])
            assertIs<AgentEvent.AgentCompleted>(collected[1])
        }

        @Test
        fun `chatStream should include relevant event types`() = runTest {
            val bus = AgentEventBus()
            val collected = mutableListOf<AgentEvent>()

            val stream = AgentEvent.StreamChunk(
                id = UUID.randomUUID().toString(),
                sessionId = "s1",
                sourceAgentId = "a1",
                timestamp = System.currentTimeMillis(),
                correlationId = "c1",
                delta = "hello",
                accumulated = "hello",
                isComplete = false
            )

            val job = launch { bus.chatStream("s1").take(2).toList(collected) }
            bus.emit(makeStarted(sessionId = "s1"))
            bus.emit(stream)
            job.join()

            assertEquals(2, collected.size)
            assertIs<AgentEvent.AgentStarted>(collected[0])
            assertIs<AgentEvent.StreamChunk>(collected[1])
        }

        @Test
        fun `approvalEvents should filter to ApprovalRequired`() = runTest {
            val bus = AgentEventBus()
            val collected = mutableListOf<AgentEvent.ApprovalRequired>()

            val approval = AgentEvent.ApprovalRequired(
                id = UUID.randomUUID().toString(),
                sessionId = "s1",
                sourceAgentId = "a1",
                timestamp = System.currentTimeMillis(),
                correlationId = "c1",
                action = "write file",
                actionType = "FILE_WRITE",
                risk = "MEDIUM",
                details = mapOf("path" to "test.kt")
            )

            val job = launch { bus.approvalEvents("s1").take(1).toList(collected) }
            bus.emit(makeEvent(sessionId = "s1")) // not approval
            bus.emit(approval)
            job.join()

            assertEquals(1, collected.size)
            assertEquals("write file", collected[0].action)
        }
    }

    @Nested
    inner class ReplayBehavior {

        @Test
        fun `should replay recent events to new subscribers`() = runTest {
            val bus = AgentEventBus()

            // Emit before subscribing
            bus.emit(makeStarted(sessionId = "s1"))
            bus.emit(makeCompleted(sessionId = "s1"))

            // New subscriber should see replayed events
            val collected = mutableListOf<AgentEvent>()
            val job = launch { bus.events.take(2).toList(collected) }
            job.join()

            assertEquals(2, collected.size)
        }
    }
}
