package pl.jclab.refio.core.agents

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.db.TaskMode
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class MultiAgentRunnerCycleDetectionTest {

    private val runner = MultiAgentRunner(AgentEventBus())

    private fun spec(name: String, vararg deps: String) = AgentSpec(
        name = name,
        task = "Task for $name",
        dependsOn = deps.toList()
    )

    @Nested
    inner class ValidGraphs {

        @Test
        fun `should accept empty specs`() {
            runner.validateDependencies(emptyList())
        }

        @Test
        fun `should accept single agent without deps`() {
            runner.validateDependencies(listOf(spec("solo")))
        }

        @Test
        fun `should accept linear chain`() {
            runner.validateDependencies(listOf(
                spec("A"),
                spec("B", "A"),
                spec("C", "B")
            ))
        }

        @Test
        fun `should accept diamond DAG`() {
            runner.validateDependencies(listOf(
                spec("A"),
                spec("B", "A"),
                spec("C", "A"),
                spec("D", "B", "C")
            ))
        }

        @Test
        fun `should accept complex DAG`() {
            runner.validateDependencies(listOf(
                spec("A"),
                spec("B"),
                spec("C", "A", "B"),
                spec("D", "A"),
                spec("E", "C", "D")
            ))
        }
    }

    @Nested
    inner class CycleDetection {

        @Test
        fun `should detect self-loop`() {
            val ex = assertFailsWith<IllegalArgumentException> {
                runner.validateDependencies(listOf(spec("A", "A")))
            }
            assertContains(ex.message!!, "Circular dependency")
            assertContains(ex.message!!, "A")
        }

        @Test
        fun `should detect two-node cycle`() {
            val ex = assertFailsWith<IllegalArgumentException> {
                runner.validateDependencies(listOf(
                    spec("A", "B"),
                    spec("B", "A")
                ))
            }
            assertContains(ex.message!!, "Circular dependency")
        }

        @Test
        fun `should detect three-node cycle`() {
            val ex = assertFailsWith<IllegalArgumentException> {
                runner.validateDependencies(listOf(
                    spec("A", "C"),
                    spec("B", "A"),
                    spec("C", "B")
                ))
            }
            assertContains(ex.message!!, "Circular dependency")
        }

        @Test
        fun `should detect cycle in larger graph`() {
            val ex = assertFailsWith<IllegalArgumentException> {
                runner.validateDependencies(listOf(
                    spec("A"),
                    spec("B", "A"),
                    spec("C", "B"),
                    spec("D", "C", "E"), // E depends on D → cycle
                    spec("E", "D")
                ))
            }
            assertContains(ex.message!!, "Circular dependency")
        }
    }

    @Nested
    inner class UnknownDependencies {

        @Test
        fun `should reject dependency on unknown agent`() {
            val ex = assertFailsWith<IllegalArgumentException> {
                runner.validateDependencies(listOf(
                    spec("A", "nonexistent")
                ))
            }
            assertContains(ex.message!!, "unknown agent")
            assertContains(ex.message!!, "nonexistent")
        }

        @Test
        fun `should list known agents in error`() {
            val ex = assertFailsWith<IllegalArgumentException> {
                runner.validateDependencies(listOf(
                    spec("A"),
                    spec("B", "missing")
                ))
            }
            assertContains(ex.message!!, "A")
            assertContains(ex.message!!, "B")
        }
    }

    @Nested
    inner class IntegrationWithRun {

        @Test
        fun `run should reject cyclic dependencies`() = runTest {
            val ex = assertFailsWith<IllegalArgumentException> {
                runner.run("session-1", listOf(
                    spec("A", "B"),
                    spec("B", "A")
                )) { _, _ ->
                    AgentResult("test", true, "ok")
                }
            }
            assertContains(ex.message!!, "Circular dependency")
        }
    }
}
