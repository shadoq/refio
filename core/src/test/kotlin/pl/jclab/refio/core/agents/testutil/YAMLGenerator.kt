package pl.jclab.refio.core.agents.testutil

import pl.jclab.refio.core.agents.AgentDefinition
import pl.jclab.refio.core.agents.MultiAgentTaskDefinition
import kotlin.random.Random

/**
 * Random MultiAgentTaskDefinition generator for property-based tests.
 */
object YAMLGenerator {

    private val NAME_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789-"

    fun randomName(random: Random): String {
        val len = random.nextInt(3, 16)
        return buildString {
            append(('a'..'z').random(random)) // Start with letter
            repeat(len - 1) { append(NAME_CHARS.random(random)) }
        }
    }

    /**
     * Generate a valid MultiAgentTaskDefinition with random names, modes, dependencies.
     * Dependencies are forward-only (agent i can only depend on agents 0..i-1) to guarantee DAG.
     */
    fun randomDefinition(
        minAgents: Int = 1,
        maxAgents: Int = 10,
        random: Random = Random
    ): MultiAgentTaskDefinition {
        val n = random.nextInt(minAgents, maxAgents + 1)
        val agentNames = mutableListOf<String>()
        while (agentNames.size < n) {
            val name = randomName(random)
            if (name !in agentNames) agentNames.add(name)
        }

        val modes = listOf("agent", "plan", "chat")
        val agents = agentNames.mapIndexed { i, name ->
            val possibleDeps = agentNames.subList(0, i)
            val numDeps = if (possibleDeps.isEmpty()) 0
                else random.nextInt(0, minOf(3, possibleDeps.size + 1))
            val deps = possibleDeps.shuffled(random).take(numDeps)

            AgentDefinition(
                name = name,
                profile = if (random.nextBoolean()) randomName(random) else null,
                task = "Task: ${randomName(random)}",
                mode = modes.random(random),
                model = if (random.nextBoolean()) "model/${randomName(random)}" else null,
                dependsOn = deps
            )
        }

        return MultiAgentTaskDefinition(
            name = "Task-${randomName(random)}",
            description = "Desc: ${randomName(random)}",
            agents = agents
        )
    }

    /**
     * Generate random garbage string (ASCII + Unicode mix) for resilience testing.
     */
    fun randomGarbage(
        minLength: Int = 0,
        maxLength: Int = 10000,
        random: Random = Random
    ): String {
        val len = random.nextInt(minLength, maxLength + 1)
        return buildString {
            repeat(len) {
                // Mix of printable ASCII, special chars, and extended Unicode
                when (random.nextInt(4)) {
                    0 -> append(random.nextInt(32, 127).toChar()) // printable ASCII
                    1 -> append("{}[]:\n\t\"'\\|/<>!@#$%^&*()".random(random)) // YAML special
                    2 -> append(random.nextInt(0x0400, 0x04FF).toChar()) // Cyrillic
                    3 -> append(random.nextInt(0x4E00, 0x9FFF).toChar()) // CJK
                }
            }
        }
    }
}
