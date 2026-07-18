package pl.jclab.refio.core.agents

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.agents.testutil.FakeAgentExecutor
import pl.jclab.refio.core.db.TaskMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MultiAgentRunnerTest {

    private val eventBus = AgentEventBus()
    private val runner = MultiAgentRunner(eventBus)

    private fun successExecutor(response: String = "done"): suspend (AgentSpec, String) -> AgentResult =
        { spec, _ ->
            AgentResult(
                agentName = spec.name,
                success = true,
                response = response,
                tokensUsed = 100,
                costUsd = 0.01,
                durationMs = 50
            )
        }

    @Nested
    inner class BasicExecution {

        @Test
        fun `should execute single agent`() = runTest {
            val specs = listOf(
                AgentSpec("analyst", task = "Analyze")
            )

            val results = runner.run("s1", specs, successExecutor())

            assertEquals(1, results.size)
            assertTrue(results["analyst"]!!.success)
        }

        @Test
        fun `should execute multiple independent agents`() = runTest {
            val specs = listOf(
                AgentSpec("agent-a", task = "Task A"),
                AgentSpec("agent-b", task = "Task B"),
                AgentSpec("agent-c", task = "Task C")
            )

            val results = runner.run("s1", specs, successExecutor())

            assertEquals(3, results.size)
            assertTrue(results.values.all { it.success })
        }

        @Test
        fun `should handle agent failure`() = runTest {
            val specs = listOf(
                AgentSpec("failing-agent", task = "Will fail")
            )

            val results = runner.run("s1", specs) { spec, _ ->
                throw RuntimeException("Agent crashed")
            }

            assertEquals(1, results.size)
            assertFalse(results["failing-agent"]!!.success)
            assertEquals("Agent crashed", results["failing-agent"]!!.error)
        }

        @Test
        fun `dependent agent is not deadlocked when upstream setup throws before execution`() = runTest {
            // If pre-execution setup (e.g. the AgentStarted emit) throws, the upstream
            // agent must still be marked completed so dependents don't wait forever.
            val failingBus = io.mockk.spyk(AgentEventBus())
            io.mockk.coEvery {
                failingBus.emit(match { it is AgentEvent.AgentStarted && it.agentName == "upstream" })
            } throws RuntimeException("event bus down")
            val runner = MultiAgentRunner(failingBus)
            val specs = listOf(
                AgentSpec("upstream", task = "Task A"),
                AgentSpec("dependent", task = "Task B", dependsOn = listOf("upstream"))
            )

            val results = withTimeout(10.seconds) {
                runner.run("s1", specs, successExecutor())
            }

            assertFalse(results["upstream"]!!.success)
            assertTrue(results["dependent"]!!.success)
        }
    }

    @Nested
    inner class DependencyResolution {

        @Test
        fun `should respect dependencies`() = runTest {
            val executionOrder = mutableListOf<String>()
            val specs = listOf(
                AgentSpec("analyst", task = "Analyze"),
                AgentSpec("coder", task = "Code", dependsOn = listOf("analyst"))
            )

            runner.run("s1", specs) { spec, _ ->
                executionOrder.add(spec.name)
                AgentResult(spec.name, true, "done", 100, 0.01)
            }

            // Analyst must complete before coder starts
            assertEquals("analyst", executionOrder[0])
            assertEquals("coder", executionOrder[1])
        }

        @Test
        fun `should handle diamond dependencies`() = runTest {
            val specs = listOf(
                AgentSpec("base", task = "Base"),
                AgentSpec("left", task = "Left", dependsOn = listOf("base")),
                AgentSpec("right", task = "Right", dependsOn = listOf("base")),
                AgentSpec("final", task = "Final", dependsOn = listOf("left", "right"))
            )

            val results = runner.run("s1", specs, successExecutor())

            assertEquals(4, results.size)
            assertTrue(results.values.all { it.success })
        }

        @Test
        fun `failed dependency should not block dependent`() = runTest {
            val specs = listOf(
                AgentSpec("base", task = "Base"),
                AgentSpec("dependent", task = "Dep", dependsOn = listOf("base"))
            )

            // Base fails but still completes (completedAgents updated)
            val results = runner.run("s1", specs) { spec, _ ->
                if (spec.name == "base") throw RuntimeException("Base failed")
                AgentResult(spec.name, true, "done", 100, 0.01)
            }

            assertEquals(2, results.size)
            assertFalse(results["base"]!!.success)
            assertTrue(results["dependent"]!!.success)
        }
    }

    @Nested
    inner class EventEmission {

        @Test
        fun `should emit AgentStarted and AgentCompleted events`() = runTest {
            val events = mutableListOf<AgentEvent>()
            val job = launch {
                eventBus.lifecycleEvents("s1").take(2).toList(events)
            }

            runner.run("s1", listOf(AgentSpec("test", task = "Test")), successExecutor())
            job.join()

            assertEquals(2, events.size)
            assertIs<AgentEvent.AgentStarted>(events[0])
            assertIs<AgentEvent.AgentCompleted>(events[1])
        }

        @Test
        fun `should emit AgentFailed on error`() = runTest {
            val events = mutableListOf<AgentEvent>()
            val job = launch {
                eventBus.lifecycleEvents("s1").take(2).toList(events)
            }

            runner.run("s1", listOf(AgentSpec("test", task = "Test"))) { _, _ ->
                throw RuntimeException("boom")
            }
            job.join()

            assertEquals(2, events.size)
            assertIs<AgentEvent.AgentStarted>(events[0])
            assertIs<AgentEvent.AgentFailed>(events[1])
            assertEquals("boom", (events[1] as AgentEvent.AgentFailed).error)
        }

        @Test
        fun `should include agent name in started event`() = runTest {
            val events = mutableListOf<AgentEvent>()
            val job = launch {
                eventBus.lifecycleEvents("s1").take(2).toList(events)
            }

            runner.run("s1", listOf(AgentSpec("analyst", task = "Analyze", model = "claude")), successExecutor())
            job.join()

            val started = events[0] as AgentEvent.AgentStarted
            assertEquals("analyst", started.agentName)
            assertEquals("claude", started.model)
            assertEquals("Analyze", started.task)
        }
    }

    @Nested
    inner class DependencyWaitTimeout {

        @Test
        fun `hanging executor prevents dependent agent from starting`() = runTest {
            val fake = FakeAgentExecutor()
            fake.configure("A", FakeAgentExecutor.AgentConfig(hang = true))
            fake.configure("B", FakeAgentExecutor.AgentConfig(delayMs = 10))

            val specs = listOf(
                AgentSpec("A", task = "Hangs forever"),
                AgentSpec("B", task = "Depends on A", dependsOn = listOf("A"))
            )

            // Current behavior: no built-in timeout on dependency wait.
            // runner.run() hangs because A never completes and B waits forever.
            // Use withTimeout to prevent test hang.
            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                withTimeout(5.seconds) {
                    runner.run("s1", specs, fake.executor)
                }
            }

            // B never started — it was waiting for A's completion
            assertTrue(
                fake.executionLog.none { it.agentName == "B" },
                "B should not have started since A never completed"
            )
        }
    }

    @Nested
    inner class PartialFailureIsolation {

        @Test
        fun `failed dependency still unblocks dependent agent`() = runTest {
            val executionOrder = mutableListOf<String>()
            val specs = listOf(
                AgentSpec("A", task = "Succeeds"),
                AgentSpec("B", task = "Fails", dependsOn = listOf("A")),
                AgentSpec("C", task = "Depends on B", dependsOn = listOf("B"))
            )

            val results = runner.run("s1", specs) { spec, _ ->
                executionOrder.add(spec.name)
                when (spec.name) {
                    "B" -> throw RuntimeException("LLM unavailable")
                    else -> AgentResult(spec.name, true, "done", 100, 0.01)
                }
            }

            // A completes successfully
            assertTrue(results["A"]!!.success)

            // B fails with error
            assertFalse(results["B"]!!.success)
            assertEquals("LLM unavailable", results["B"]!!.error)

            // C runs because B is added to completedAgents in finally block
            // (current behavior: failed agents still unblock dependents)
            assertTrue(results.containsKey("C"), "C should have run after B failed")
            assertTrue(results["C"]!!.success)

            // Execution order: A first, then B, then C
            assertEquals("A", executionOrder[0])
            assertEquals("B", executionOrder[1])
            assertEquals("C", executionOrder[2])
        }

        @Test
        fun `no ConcurrentModificationException on results map`() = runTest {
            // Stress test: many agents completing concurrently
            val specs = (0 until 10).map { AgentSpec("agent-$it", task = "Task $it") }

            val results = runner.run("s1", specs) { spec, _ ->
                delay(10) // Small delay to increase interleaving
                AgentResult(spec.name, true, "done", 100, 0.01)
            }

            assertEquals(10, results.size)
            assertTrue(results.values.all { it.success })
        }
    }
}
