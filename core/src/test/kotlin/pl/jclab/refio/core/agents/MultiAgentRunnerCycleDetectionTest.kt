package pl.jclab.refio.core.agents

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.agents.testutil.DAGGenerator
import pl.jclab.refio.core.db.TaskMode
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

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

    @Nested
    inner class PropertyBasedValidation {

        @Test
        fun `random graphs — validateDependencies matches independent topo sort`() {
            val random = Random(42) // Fixed seed for reproducibility
            var cyclesDetected = 0
            var validGraphs = 0

            repeat(500) { i ->
                val specs = DAGGenerator.randomGraph(1, 20, 3, random)

                // Independent check: try topological sort
                val isDAG = isTopologicallySortable(specs)

                try {
                    runner.validateDependencies(specs)
                    // If validation succeeds, graph must be a DAG
                    assertTrue(isDAG,
                        "Iteration $i: validateDependencies passed but graph has cycle: " +
                        specs.map { "${it.name}->[${it.dependsOn.joinToString()}]" })
                    validGraphs++
                } catch (e: IllegalArgumentException) {
                    // If validation fails, it should be because of cycle or unknown dep
                    val msg = e.message ?: ""
                    assertTrue(
                        msg.contains("Circular dependency") || msg.contains("unknown agent"),
                        "Iteration $i: unexpected error: $msg"
                    )
                    if (msg.contains("Circular dependency")) {
                        // At least one agent name from graph should be in the message
                        val anyAgentMentioned = specs.any { msg.contains(it.name) }
                        assertTrue(anyAgentMentioned,
                            "Cycle error should mention at least one agent name")
                        cyclesDetected++
                    }
                }
            }

            // Sanity: we should have found both valid and cyclic graphs
            assertTrue(validGraphs > 0, "Should have found at least some valid graphs")
            assertTrue(cyclesDetected > 0, "Should have found at least some cycles")
        }

        @Test
        fun `guaranteed valid DAGs — validateDependencies never throws`() {
            val random = Random(42)
            repeat(500) { i ->
                val specs = DAGGenerator.randomDAG(1, 20, 3, random)
                try {
                    runner.validateDependencies(specs)
                } catch (e: Exception) {
                    fail("Iteration $i: DAG validation should never throw, but got: ${e.message}\n" +
                        "Specs: ${specs.map { "${it.name}->[${it.dependsOn.joinToString()}]" }}")
                }
            }
        }

        /**
         * Independent topological sort using Kahn's algorithm.
         * Returns true if the graph is a DAG (no cycles).
         */
        private fun isTopologicallySortable(specs: List<AgentSpec>): Boolean {
            val names = specs.map { it.name }.toSet()
            // Check for unknown dependencies first
            for (spec in specs) {
                for (dep in spec.dependsOn) {
                    if (dep !in names) return false
                }
            }

            val inDegree = specs.associate { it.name to it.dependsOn.size }.toMutableMap()
            val adjacency = mutableMapOf<String, MutableList<String>>()
            for (spec in specs) {
                for (dep in spec.dependsOn) {
                    adjacency.getOrPut(dep) { mutableListOf() }.add(spec.name)
                }
            }

            val queue = ArrayDeque(inDegree.filter { it.value == 0 }.keys)
            var sorted = 0
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                sorted++
                for (neighbor in adjacency[node] ?: emptyList()) {
                    inDegree[neighbor] = inDegree[neighbor]!! - 1
                    if (inDegree[neighbor] == 0) queue.add(neighbor)
                }
            }
            return sorted == specs.size
        }
    }
}
