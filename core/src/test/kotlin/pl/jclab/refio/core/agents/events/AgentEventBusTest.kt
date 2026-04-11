package pl.jclab.refio.core.agents.events

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.repositories.AgentEventSqlRepository
import pl.jclab.refio.testutil.TestDatabase
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
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

    private fun makeFailed(
        sessionId: String = "s1",
        agentId: String = "a1"
    ): AgentEvent.AgentFailed = AgentEvent.AgentFailed(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        sourceAgentId = agentId,
        timestamp = System.currentTimeMillis(),
        correlationId = "c1",
        error = "test error",
        recoverable = true
    )

    private var tsCounter = 1000L

    private fun makeTurnStarted(
        sessionId: String = "s1",
        agentId: String = "a1"
    ): AgentEvent.TurnStarted = AgentEvent.TurnStarted(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        sourceAgentId = agentId,
        timestamp = tsCounter++,
        correlationId = "c1",
        iteration = 1,
        maxIterations = 10,
        mode = "AGENT",
        runId = UUID.randomUUID().toString(),
        parentRunId = null,
        depth = 0
    )

    private fun makeLLMCallCompleted(
        sessionId: String = "s1",
        agentId: String = "a1"
    ): AgentEvent.LLMCallCompleted = AgentEvent.LLMCallCompleted(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        sourceAgentId = agentId,
        timestamp = tsCounter++,
        correlationId = "c1",
        iteration = 1,
        model = "test-model",
        provider = "test-provider",
        tokensIn = 100,
        tokensOut = 50,
        costUsd = 0.01,
        durationMs = 500,
        finishReason = "stop",
        runId = UUID.randomUUID().toString(),
        parentRunId = null,
        depth = 0
    )

    @Nested
    inner class PersistenceResilience {

        @Test
        fun `persistence failure should not block emission`() = runTest {
            val mockRepo = mockk<AgentEventRepository> {
                coEvery { save(any()) } throws RuntimeException("DB is down")
            }
            val bus = AgentEventBus()
            bus.setRepository(mockRepo)

            val collected = mutableListOf<AgentEvent>()
            val job = launch { bus.events.take(1).toList(collected) }

            // emit should succeed despite repository failure
            bus.emit(makeStarted())
            job.join()

            assertEquals(1, collected.size)
            assertIs<AgentEvent.AgentStarted>(collected[0])
        }

        @Test
        fun `should load persisted events after session restart`() = runTest {
            val db = TestDatabase.createSharedInMemory()
            try {
                val repo = AgentEventSqlRepository()
                val bus1 = AgentEventBus()
                bus1.setRepository(repo)

                // Use monotonic timestamps for ordering
                var ts = 1000L
                val originalEvents = listOf(
                    AgentEvent.AgentStarted(
                        id = "e1", sessionId = "s1", sourceAgentId = "a1",
                        timestamp = ts++, correlationId = "c1",
                        agentName = "analyzer", profile = "code-analyzer",
                        task = "Analyze", model = "gpt-4o", dependsOn = listOf("base")
                    ),
                    AgentEvent.TurnStarted(
                        id = "e2", sessionId = "s1", sourceAgentId = "a1",
                        timestamp = ts++, correlationId = "c1",
                        iteration = 1, maxIterations = 25, mode = "AGENT",
                        runId = "run-1", parentRunId = null, depth = 0
                    ),
                    AgentEvent.LLMCallCompleted(
                        id = "e3", sessionId = "s1", sourceAgentId = "a1",
                        timestamp = ts++, correlationId = "c1",
                        iteration = 1, model = "gpt-4o", provider = "openai",
                        tokensIn = 500, tokensOut = 200, costUsd = 0.02,
                        durationMs = 3000, finishReason = "stop",
                        runId = "run-1", parentRunId = null, depth = 0
                    ),
                    AgentEvent.AgentCompleted(
                        id = "e4", sessionId = "s1", sourceAgentId = "a1",
                        timestamp = ts++, correlationId = "c1",
                        summary = "Analysis done", artifacts = emptyList(),
                        tokensUsed = 700, costUsd = 0.02, durationMs = 5000
                    ),
                    AgentEvent.AgentStarted(
                        id = "e5", sessionId = "s1", sourceAgentId = "a2",
                        timestamp = ts++, correlationId = "c1",
                        agentName = "coder", profile = null,
                        task = "Implement", model = null, dependsOn = emptyList()
                    ),
                    AgentEvent.TurnStarted(
                        id = "e6", sessionId = "s1", sourceAgentId = "a2",
                        timestamp = ts++, correlationId = "c1",
                        iteration = 1, maxIterations = 50, mode = "AGENT",
                        runId = "run-2", parentRunId = null, depth = 0
                    ),
                    AgentEvent.LLMCallCompleted(
                        id = "e7", sessionId = "s1", sourceAgentId = "a2",
                        timestamp = ts++, correlationId = "c1",
                        iteration = 1, model = "claude", provider = "anthropic",
                        tokensIn = 800, tokensOut = 400, costUsd = 0.05,
                        durationMs = 4000, finishReason = "end_turn",
                        runId = "run-2", parentRunId = null, depth = 0
                    ),
                    AgentEvent.AgentFailed(
                        id = "e8", sessionId = "s1", sourceAgentId = "a2",
                        timestamp = ts++, correlationId = "c1",
                        error = "Tool execution failed", recoverable = true
                    ),
                    AgentEvent.AgentStarted(
                        id = "e9", sessionId = "s1", sourceAgentId = "a3",
                        timestamp = ts++, correlationId = "c1",
                        agentName = "tester", profile = null,
                        task = "Test", model = null, dependsOn = emptyList()
                    ),
                    AgentEvent.AgentCompleted(
                        id = "e10", sessionId = "s1", sourceAgentId = "a3",
                        timestamp = ts++, correlationId = "c1",
                        summary = "Tests pass", artifacts = emptyList(),
                        tokensUsed = 300, costUsd = 0.01, durationMs = 2000
                    )
                )

                originalEvents.forEach { bus1.emit(it) }

                // Create fresh EventBus with same repo, load persisted events
                val bus2 = AgentEventBus()
                bus2.setRepository(repo)
                val loaded = bus2.loadPersistedEvents("s1")

                // All 10 events returned
                assertEquals(10, loaded.size)

                // Correct chronological order
                for (i in 0 until loaded.size - 1) {
                    assertTrue(
                        loaded[i].timestamp <= loaded[i + 1].timestamp,
                        "Events not in chronological order at index $i"
                    )
                }

                // Event types preserved through serialization round-trip
                assertIs<AgentEvent.AgentStarted>(loaded[0])
                assertIs<AgentEvent.TurnStarted>(loaded[1])
                assertIs<AgentEvent.LLMCallCompleted>(loaded[2])
                assertIs<AgentEvent.AgentCompleted>(loaded[3])
                assertIs<AgentEvent.AgentStarted>(loaded[4])
                assertIs<AgentEvent.TurnStarted>(loaded[5])
                assertIs<AgentEvent.LLMCallCompleted>(loaded[6])
                assertIs<AgentEvent.AgentFailed>(loaded[7])
                assertIs<AgentEvent.AgentStarted>(loaded[8])
                assertIs<AgentEvent.AgentCompleted>(loaded[9])

                // Key fields preserved
                val started = loaded[0] as AgentEvent.AgentStarted
                assertEquals("analyzer", started.agentName)
                assertEquals("code-analyzer", started.profile)
                assertEquals("gpt-4o", started.model)
                assertEquals(listOf("base"), started.dependsOn)

                val llmCall = loaded[2] as AgentEvent.LLMCallCompleted
                assertEquals(500, llmCall.tokensIn)
                assertEquals(200, llmCall.tokensOut)
                assertEquals("openai", llmCall.provider)
                assertEquals("run-1", llmCall.runId)
            } finally {
                db.keepAlive.close()
            }
        }
    }

    @Nested
    inner class ConcurrentEmission {

        @Test
        fun `concurrent emission preserves properties`() = runTest {
            val random = Random(42)
            repeat(100) { iteration ->
                val bus = AgentEventBus()
                val n = random.nextInt(2, 11) // 2-10 coroutines
                val m = random.nextInt(5, 21) // 5-20 events each

                val collected = CopyOnWriteArrayList<AgentEvent>()
                val collectJob = launch { bus.events.collect { collected.add(it) } }

                val emitJobs = (0 until n).map { i ->
                    launch {
                        repeat(m) { j ->
                            bus.emit(AgentEvent.ProgressUpdate(
                                id = "iter${iteration}-c${i}-e${j}",
                                sessionId = "s-$i",
                                sourceAgentId = "a-$i",
                                timestamp = System.currentTimeMillis(),
                                correlationId = "c1",
                                phase = "test",
                                message = "msg-$j",
                                progress = null
                            ))
                        }
                    }
                }
                emitJobs.forEach { it.join() }

                // Give collector time to process
                delay(50)
                collectJob.cancel()

                val expectedTotal = n * m

                // All events collected (within buffer limits)
                assertTrue(collected.size >= expectedTotal,
                    "Iteration $iteration: expected $expectedTotal events, got ${collected.size}")

                // No duplicate events (by unique ID)
                val uniqueIds = collected.map { it.id }.toSet()
                assertEquals(collected.size, uniqueIds.size,
                    "Iteration $iteration: duplicate events detected")

                // Session filtering: sessionEvents("s-0") only contains s-0 events
                val session0Events = collected.filter { it.sessionId == "s-0" }
                assertTrue(session0Events.all { it.sourceAgentId == "a-0" },
                    "Iteration $iteration: session filter leaked events from other agents")
            }
        }
    }
}
