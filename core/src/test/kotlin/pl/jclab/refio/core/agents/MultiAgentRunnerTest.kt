package pl.jclab.refio.core.agents

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.db.TaskMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
}
