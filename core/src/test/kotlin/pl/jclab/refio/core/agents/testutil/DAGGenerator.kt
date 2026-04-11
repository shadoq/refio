package pl.jclab.refio.core.agents.testutil

import pl.jclab.refio.core.agents.AgentSpec
import kotlin.random.Random

/**
 * Random graph generator for property-based tests.
 */
object DAGGenerator {

    /**
     * Generate a guaranteed-acyclic DAG.
     * Each node can only depend on nodes with LOWER indices (forward-only edges).
     */
    fun randomDAG(
        minNodes: Int = 2,
        maxNodes: Int = 8,
        maxDepsPerNode: Int = 2,
        random: Random = Random
    ): List<AgentSpec> {
        val n = random.nextInt(minNodes, maxNodes + 1)
        return (0 until n).map { i ->
            val name = "agent-$i"
            val possibleDeps = (0 until i).map { "agent-$it" }
            val numDeps = if (possibleDeps.isEmpty()) 0
                else random.nextInt(0, minOf(maxDepsPerNode + 1, possibleDeps.size + 1))
            val deps = possibleDeps.shuffled(random).take(numDeps)
            AgentSpec(name = name, task = "Task for $name", dependsOn = deps)
        }
    }

    /**
     * Generate a random graph that MAY contain cycles.
     * Any node can depend on any other node (including itself).
     */
    fun randomGraph(
        minNodes: Int = 2,
        maxNodes: Int = 10,
        maxEdgesPerNode: Int = 3,
        random: Random = Random
    ): List<AgentSpec> {
        val n = random.nextInt(minNodes, maxNodes + 1)
        val names = (0 until n).map { "agent-$it" }
        return names.map { name ->
            val others = names // Can include self for self-loops
            val numDeps = random.nextInt(0, minOf(maxEdgesPerNode + 1, others.size + 1))
            val deps = others.shuffled(random).take(numDeps).filter { it != name || random.nextBoolean() }
            AgentSpec(name = name, task = "Task for $name", dependsOn = deps)
        }
    }
}
