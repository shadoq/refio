package pl.jclab.refio.core.agents

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.agents.testutil.DAGGenerator
import pl.jclab.refio.core.agents.testutil.FakeAgentExecutor
import pl.jclab.refio.core.agents.testutil.TestEventCollector
import pl.jclab.refio.core.db.repositories.AgentEventSqlRepository
import pl.jclab.refio.testutil.TestDatabase
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end multi-agent scenario tests.
 * Uses real AgentEventBus, real MultiAgentRunner, FakeAgentExecutor.
 */
class MultiAgentScenarioTest {

    private lateinit var eventBus: AgentEventBus
    private lateinit var runner: MultiAgentRunner
    private lateinit var collector: TestEventCollector

    @Nested
    inner class HappyPathParallelAgents {

        @Test
        fun `three independent agents complete in parallel`() = runTest {
            eventBus = AgentEventBus()
            runner = MultiAgentRunner(eventBus)
            collector = TestEventCollector(eventBus, this)

            val fake = FakeAgentExecutor()
            fake.configure("analyzer", FakeAgentExecutor.AgentConfig(
                delayMs = 50, tokensUsed = 100, costUsd = 0.01
            ))
            fake.configure("reviewer", FakeAgentExecutor.AgentConfig(
                delayMs = 100, tokensUsed = 200, costUsd = 0.02
            ))
            fake.configure("tester", FakeAgentExecutor.AgentConfig(
                delayMs = 75, tokensUsed = 300, costUsd = 0.03
            ))

            val specs = listOf(
                AgentSpec("analyzer", task = "Analyze codebase"),
                AgentSpec("reviewer", task = "Review code"),
                AgentSpec("tester", task = "Run tests")
            )

            val results = runner.run("s1", specs, fake.executor)

            // All 3 complete successfully
            assertEquals(3, results.size)
            assertTrue(results.values.all { it.success })

            // EventBus contains 3x AgentStarted + 3x AgentCompleted
            val started = collector.eventsOfType<AgentEvent.AgentStarted>()
            val completed = collector.eventsOfType<AgentEvent.AgentCompleted>()
            assertEquals(3, started.size)
            assertEquals(3, completed.size)

            // Total tokens across AgentCompleted events = 600
            val totalTokens = completed.sumOf { it.tokensUsed }
            assertEquals(600L, totalTokens)

            // Results map has all 3 entries
            assertNotNull(results["analyzer"])
            assertNotNull(results["reviewer"])
            assertNotNull(results["tester"])

            collector.stop()
        }

        @Test
        fun `events persisted to DB and recoverable`() = runTest {
            val db = TestDatabase.createSharedInMemory()
            try {
                eventBus = AgentEventBus()
                val repo = AgentEventSqlRepository()
                eventBus.setRepository(repo)
                runner = MultiAgentRunner(eventBus)

                val fake = FakeAgentExecutor(FakeAgentExecutor.AgentConfig(
                    delayMs = 10, tokensUsed = 100, costUsd = 0.01
                ))

                val specs = listOf(
                    AgentSpec("analyzer", task = "Analyze"),
                    AgentSpec("reviewer", task = "Review"),
                    AgentSpec("tester", task = "Test")
                )

                runner.run("s1", specs, fake.executor)

                // Persistence is async (emit queues saves onto Dispatchers.IO);
                // drain the queue before reading so the assertions are deterministic.
                eventBus.flushPersistence()

                // Recover events from DB
                val loaded = eventBus.loadPersistedEvents("s1")
                assertTrue(loaded.size >= 6, "Expected at least 6 events (3 started + 3 completed), got ${loaded.size}")

                val loadedStarted = loaded.filterIsInstance<AgentEvent.AgentStarted>()
                val loadedCompleted = loaded.filterIsInstance<AgentEvent.AgentCompleted>()
                assertEquals(3, loadedStarted.size)
                assertEquals(3, loadedCompleted.size)
            } finally {
                db.keepAlive.close()
            }
        }
    }

    @Nested
    inner class LinearChain {

        @Test
        fun `agents execute in dependency order A then B then C`() = runTest {
            eventBus = AgentEventBus()
            runner = MultiAgentRunner(eventBus)
            collector = TestEventCollector(eventBus, this)

            val fake = FakeAgentExecutor(FakeAgentExecutor.AgentConfig(delayMs = 100))

            val specs = listOf(
                AgentSpec("A", task = "First"),
                AgentSpec("B", task = "Second", dependsOn = listOf("A")),
                AgentSpec("C", task = "Third", dependsOn = listOf("B"))
            )

            runner.run("s1", specs, fake.executor)

            // Verify execution order via order counter
            val logA = fake.executionLog.first { it.agentName == "A" }
            val logB = fake.executionLog.first { it.agentName == "B" }
            val logC = fake.executionLog.first { it.agentName == "C" }

            // A completes before B starts, B completes before C starts
            assertTrue(logA.endOrder < logB.startOrder,
                "A (endOrder=${logA.endOrder}) should complete before B starts (startOrder=${logB.startOrder})")
            assertTrue(logB.endOrder < logC.startOrder,
                "B (endOrder=${logB.endOrder}) should complete before C starts (startOrder=${logC.startOrder})")

            // Event order: Started(A), Completed(A), Started(B), Completed(B), Started(C), Completed(C)
            collector.assertEventOrder(
                AgentEvent.AgentStarted::class,
                AgentEvent.AgentCompleted::class,
                AgentEvent.AgentStarted::class,
                AgentEvent.AgentCompleted::class,
                AgentEvent.AgentStarted::class,
                AgentEvent.AgentCompleted::class
            )

            collector.stop()
        }
    }

    @Nested
    inner class DiamondDependency {

        @Test
        fun `diamond A then B and C in parallel then D`() = runTest {
            eventBus = AgentEventBus()
            runner = MultiAgentRunner(eventBus)

            val fake = FakeAgentExecutor()
            fake.configure("A", FakeAgentExecutor.AgentConfig(delayMs = 100))
            fake.configure("B", FakeAgentExecutor.AgentConfig(delayMs = 150))
            fake.configure("C", FakeAgentExecutor.AgentConfig(delayMs = 200))
            fake.configure("D", FakeAgentExecutor.AgentConfig(delayMs = 50))

            val specs = listOf(
                AgentSpec("A", task = "Base"),
                AgentSpec("B", task = "Left", dependsOn = listOf("A")),
                AgentSpec("C", task = "Right", dependsOn = listOf("A")),
                AgentSpec("D", task = "Final", dependsOn = listOf("B", "C"))
            )

            val results = runner.run("s1", specs, fake.executor)

            // All 4 succeed
            assertEquals(4, results.size)
            assertTrue(results.values.all { it.success })

            val logA = fake.executionLog.first { it.agentName == "A" }
            val logB = fake.executionLog.first { it.agentName == "B" }
            val logC = fake.executionLog.first { it.agentName == "C" }
            val logD = fake.executionLog.first { it.agentName == "D" }

            // A completes before B and C start
            assertTrue(logA.endOrder < logB.startOrder, "A should complete before B starts")
            assertTrue(logA.endOrder < logC.startOrder, "A should complete before C starts")

            // D starts only after both B and C complete
            assertTrue(logB.endOrder < logD.startOrder, "B should complete before D starts")
            assertTrue(logC.endOrder < logD.startOrder, "C should complete before D starts")
        }
    }

    @Nested
    inner class PartialFailureInChain {

        @Test
        fun `failed dependency B still allows C to run`() = runTest {
            eventBus = AgentEventBus()
            runner = MultiAgentRunner(eventBus)
            collector = TestEventCollector(eventBus, this)

            val fake = FakeAgentExecutor(FakeAgentExecutor.AgentConfig(delayMs = 10))
            fake.configure("B", FakeAgentExecutor.AgentConfig(
                throwException = RuntimeException("LLM unavailable")
            ))

            val specs = listOf(
                AgentSpec("A", task = "First"),
                AgentSpec("B", task = "Fails", dependsOn = listOf("A")),
                AgentSpec("C", task = "After B", dependsOn = listOf("B"))
            )

            val results = runner.run("s1", specs, fake.executor)

            // A completes successfully
            assertTrue(results["A"]!!.success)

            // B fails with error
            assertFalse(results["B"]!!.success)
            assertEquals("LLM unavailable", results["B"]!!.error)

            // AgentFailed event emitted for B
            val failedEvents = collector.eventsOfType<AgentEvent.AgentFailed>()
            assertEquals(1, failedEvents.size)
            assertEquals("LLM unavailable", failedEvents[0].error)

            // C runs (B added to completedAgents in finally block)
            assertTrue(results.containsKey("C"),
                "C should run — failed agents are added to completedAgents in finally block")
            assertTrue(results["C"]!!.success)

            collector.stop()
        }
    }

    @Nested
    inner class IndependentFailureIsolation {

        @Test
        fun `failed independent agent does not affect others`() = runTest {
            eventBus = AgentEventBus()
            runner = MultiAgentRunner(eventBus)
            collector = TestEventCollector(eventBus, this)

            val fake = FakeAgentExecutor(FakeAgentExecutor.AgentConfig(delayMs = 50))
            fake.configure("B", FakeAgentExecutor.AgentConfig(
                throwException = RuntimeException("B crashed")
            ))

            val specs = listOf(
                AgentSpec("A", task = "Independent A"),
                AgentSpec("B", task = "Will crash"),
                AgentSpec("C", task = "Independent C")
            )

            val results = runner.run("s1", specs, fake.executor)

            // A and C succeed despite B failing (supervisorScope isolation)
            assertTrue(results["A"]!!.success)
            assertFalse(results["B"]!!.success)
            assertTrue(results["C"]!!.success)

            // Events: 3x Started, 1x Failed (B), 2x Completed (A, C)
            val started = collector.eventsOfType<AgentEvent.AgentStarted>()
            val completed = collector.eventsOfType<AgentEvent.AgentCompleted>()
            val failed = collector.eventsOfType<AgentEvent.AgentFailed>()
            assertEquals(3, started.size)
            assertEquals(2, completed.size)
            assertEquals(1, failed.size)

            collector.stop()
        }
    }

    @Nested
    inner class SubagentNesting {

        @Test
        fun `turn events track depth and parent chain`() = runTest {
            eventBus = AgentEventBus()
            runner = MultiAgentRunner(eventBus)
            collector = TestEventCollector(eventBus, this)

            val specs = listOf(AgentSpec("orchestrator", task = "Orchestrate"))

            // Custom executor that emits turn events simulating subagent nesting
            runner.run("s1", specs) { spec, agentId ->
                val runId0 = "run-depth-0"
                val runId1 = "run-depth-1"
                val runId2 = "run-depth-2"

                // Depth 0: main turn
                eventBus.emit(AgentEvent.TurnStarted(
                    id = "ts-0", sessionId = "s1", sourceAgentId = agentId,
                    timestamp = System.currentTimeMillis(), correlationId = "s1",
                    iteration = 1, maxIterations = 50, mode = "AGENT",
                    runId = runId0, parentRunId = null, depth = 0
                ))
                // Depth 1: subagent
                eventBus.emit(AgentEvent.TurnStarted(
                    id = "ts-1", sessionId = "s1", sourceAgentId = agentId,
                    timestamp = System.currentTimeMillis(), correlationId = "s1",
                    iteration = 1, maxIterations = 25, mode = "PLAN",
                    runId = runId1, parentRunId = runId0, depth = 1
                ))
                // Depth 2: sub-subagent
                eventBus.emit(AgentEvent.TurnStarted(
                    id = "ts-2", sessionId = "s1", sourceAgentId = agentId,
                    timestamp = System.currentTimeMillis(), correlationId = "s1",
                    iteration = 1, maxIterations = 10, mode = "PLAN",
                    runId = runId2, parentRunId = runId1, depth = 2
                ))
                // End depth 2
                eventBus.emit(AgentEvent.TurnEnded(
                    id = "te-2", sessionId = "s1", sourceAgentId = agentId,
                    timestamp = System.currentTimeMillis(), correlationId = "s1",
                    iteration = 1, durationMs = 100, isFinal = true,
                    runId = runId2, parentRunId = runId1, depth = 2
                ))
                // End depth 1
                eventBus.emit(AgentEvent.TurnEnded(
                    id = "te-1", sessionId = "s1", sourceAgentId = agentId,
                    timestamp = System.currentTimeMillis(), correlationId = "s1",
                    iteration = 1, durationMs = 200, isFinal = true,
                    runId = runId1, parentRunId = runId0, depth = 1
                ))
                // End depth 0
                eventBus.emit(AgentEvent.TurnEnded(
                    id = "te-0", sessionId = "s1", sourceAgentId = agentId,
                    timestamp = System.currentTimeMillis(), correlationId = "s1",
                    iteration = 1, durationMs = 500, isFinal = true,
                    runId = runId0, parentRunId = null, depth = 0
                ))

                AgentResult(spec.name, true, "done", 500, 0.05)
            }

            val turnStarted = collector.eventsOfType<AgentEvent.TurnStarted>()
            val turnEnded = collector.eventsOfType<AgentEvent.TurnEnded>()

            assertEquals(3, turnStarted.size)
            assertEquals(3, turnEnded.size)

            // Depth 0: parentRunId = null
            val ts0 = turnStarted.first { it.depth == 0 }
            assertNull(ts0.parentRunId)
            assertEquals("run-depth-0", ts0.runId)

            // Depth 1: parentRunId = depth-0 runId
            val ts1 = turnStarted.first { it.depth == 1 }
            assertEquals("run-depth-0", ts1.parentRunId)
            assertEquals("run-depth-1", ts1.runId)

            // Depth 2: parentRunId = depth-1 runId
            val ts2 = turnStarted.first { it.depth == 2 }
            assertEquals("run-depth-1", ts2.parentRunId)
            assertEquals("run-depth-2", ts2.runId)

            // Verify tree structure: every non-root runId has a parent in the set
            val allRunIds = turnStarted.mapNotNull { it.runId }.toSet()
            for (ts in turnStarted) {
                if (ts.parentRunId != null) {
                    assertTrue(ts.parentRunId in allRunIds,
                        "parentRunId ${ts.parentRunId} not found in runIds: $allRunIds")
                }
            }

            collector.stop()
        }
    }

    @Nested
    inner class EventReplay {

        @Test
        fun `full session reconstructable from persisted events`() = runTest {
            val db = TestDatabase.createSharedInMemory()
            try {
                eventBus = AgentEventBus()
                val repo = AgentEventSqlRepository()
                eventBus.setRepository(repo)
                runner = MultiAgentRunner(eventBus)
                collector = TestEventCollector(eventBus, this)

                val fake = FakeAgentExecutor()
                fake.configure("A", FakeAgentExecutor.AgentConfig(delayMs = 100))
                fake.configure("B", FakeAgentExecutor.AgentConfig(delayMs = 150))
                fake.configure("C", FakeAgentExecutor.AgentConfig(delayMs = 200))
                fake.configure("D", FakeAgentExecutor.AgentConfig(delayMs = 50))

                val specs = listOf(
                    AgentSpec("A", task = "Base"),
                    AgentSpec("B", task = "Left", dependsOn = listOf("A")),
                    AgentSpec("C", task = "Right", dependsOn = listOf("A")),
                    AgentSpec("D", task = "Final", dependsOn = listOf("B", "C"))
                )

                runner.run("s1", specs, fake.executor)

                // Persistence is async (emit queues saves onto Dispatchers.IO);
                // drain the queue before reading so the assertions are deterministic.
                eventBus.flushPersistence()

                val originalEvents = collector.events.toList()

                // Create fresh EventBus, load from DB
                val bus2 = AgentEventBus()
                bus2.setRepository(repo)
                val loaded = bus2.loadPersistedEvents("s1")

                // Same count
                assertEquals(originalEvents.size, loaded.size,
                    "Loaded events count should match original")

                // Chronological order
                for (i in 0 until loaded.size - 1) {
                    assertTrue(loaded[i].timestamp <= loaded[i + 1].timestamp)
                }

                // Agent statuses reconstructable
                val completedAgents = loaded.filterIsInstance<AgentEvent.AgentCompleted>()
                    .map { it.sourceAgentId }
                val failedAgents = loaded.filterIsInstance<AgentEvent.AgentFailed>()
                    .map { it.sourceAgentId }
                val startedAgents = loaded.filterIsInstance<AgentEvent.AgentStarted>()
                    .map { it.agentName }

                assertEquals(4, startedAgents.size)
                assertEquals(4, completedAgents.size)
                assertEquals(0, failedAgents.size)

                collector.stop()
            } finally {
                db.keepAlive.close()
            }
        }
    }

    @Nested
    inner class Cancellation {

        @Test
        fun `cancellation stops running agents`() = runTest {
            eventBus = AgentEventBus()
            runner = MultiAgentRunner(eventBus)
            collector = TestEventCollector(eventBus, this)

            val fake = FakeAgentExecutor()
            fake.configure("A", FakeAgentExecutor.AgentConfig(delayMs = 50))
            fake.configure("B", FakeAgentExecutor.AgentConfig(delayMs = 5000)) // Slow
            fake.configure("C", FakeAgentExecutor.AgentConfig(delayMs = 50))

            val specs = listOf(
                AgentSpec("A", task = "Fast A"),
                AgentSpec("B", task = "Slow B"),
                AgentSpec("C", task = "Fast C")
            )

            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                withTimeout(100) {
                    runner.run("s1", specs, fake.executor)
                }
            }

            // A and C may have completed (fast enough: 50ms < 100ms timeout)
            val completedNames = fake.executionLog.map { it.agentName }
            // Note: timing-dependent, A and C should complete before timeout
            // B is interrupted mid-execution (5000ms >> 100ms timeout)
            assertFalse("B" in completedNames,
                "B should NOT complete — cancelled at 100ms into 5000ms delay")

            // Events emitted before cancellation are present
            val startedEvents = collector.eventsOfType<AgentEvent.AgentStarted>()
            assertTrue(startedEvents.isNotEmpty(), "At least some AgentStarted events should be present")

            collector.stop()
        }
    }

    @Nested
    inner class MetricsAggregation {

        @Test
        fun `per-agent token counts and costs in completed events`() = runTest {
            eventBus = AgentEventBus()
            runner = MultiAgentRunner(eventBus)
            collector = TestEventCollector(eventBus, this)

            val fake = FakeAgentExecutor()
            fake.configure("A", FakeAgentExecutor.AgentConfig(
                delayMs = 10, tokensUsed = 100, costUsd = 0.01
            ))
            fake.configure("B", FakeAgentExecutor.AgentConfig(
                delayMs = 10, tokensUsed = 200, costUsd = 0.02
            ))
            fake.configure("C", FakeAgentExecutor.AgentConfig(
                delayMs = 10, tokensUsed = 300, costUsd = 0.03
            ))

            val specs = listOf(
                AgentSpec("A", task = "Task A"),
                AgentSpec("B", task = "Task B"),
                AgentSpec("C", task = "Task C")
            )

            runner.run("s1", specs, fake.executor)

            val completed = collector.eventsOfType<AgentEvent.AgentCompleted>()
            assertEquals(3, completed.size)

            // Per-agent token counts
            val tokensByAgent = completed.associate {
                val name = fake.executionLog.first { r -> r.agentId == it.sourceAgentId }.agentName
                name to it.tokensUsed
            }
            assertEquals(100L, tokensByAgent["A"])
            assertEquals(200L, tokensByAgent["B"])
            assertEquals(300L, tokensByAgent["C"])

            // Session-level aggregate
            val totalTokens = completed.sumOf { it.tokensUsed }
            assertEquals(600L, totalTokens)

            val totalCost = completed.sumOf { it.costUsd }
            assertEquals(0.06, totalCost, 0.001)

            collector.stop()
        }
    }

    @Nested
    inner class PropertyBasedTopologicalOrder {

        @Test
        fun `random DAGs — dependencies always respected in execution order`() = runTest {
            val random = Random(42)
            repeat(200) { iteration ->
                val specs = DAGGenerator.randomDAG(2, 8, 2, random)
                val localEventBus = AgentEventBus()
                val localRunner = MultiAgentRunner(localEventBus)
                val fake = FakeAgentExecutor(FakeAgentExecutor.AgentConfig(delayMs = 10))

                localRunner.run("s-$iteration", specs, fake.executor)

                // For every agent with dependencies, verify execution order
                for (spec in specs) {
                    if (spec.dependsOn.isEmpty()) continue
                    val thisRecord = fake.executionLog.firstOrNull { it.agentName == spec.name }
                        ?: fail("Iteration $iteration: agent ${spec.name} not found in execution log")

                    for (dep in spec.dependsOn) {
                        val depRecord = fake.executionLog.firstOrNull { it.agentName == dep }
                            ?: fail("Iteration $iteration: dependency $dep not found in execution log")

                        assertTrue(
                            thisRecord.startOrder >= depRecord.endOrder,
                            "Iteration $iteration: ${spec.name} (startOrder=${thisRecord.startOrder}) " +
                            "started before dependency $dep completed (endOrder=${depRecord.endOrder})"
                        )
                    }
                }

                fake.reset()
            }
        }
    }
}
