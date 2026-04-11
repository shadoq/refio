package pl.jclab.refio.api.models

/**
 * Multi-agent orchestration strategy.
 *
 * Controls how multiple agents are coordinated:
 * - SINGLE: One agent with subagent calls (default, no multi-agent orchestration)
 * - PARALLEL: Multiple agents run concurrently, results collected by orchestrator
 * - PIPELINE: Sequential chain (A → B → C), each agent uses previous agent's output
 * - ORCHESTRATOR: LLM-driven dynamic orchestration — orchestrator decides at runtime
 *   which agents to spawn, in what order, and how to combine results
 */
enum class MultiAgentStrategy(val displayName: String) {
    SINGLE("Single agent"),
    PARALLEL("Parallel"),
    PIPELINE("Pipeline"),
    ORCHESTRATOR("Orchestrator (LLM)");

    companion object {
        fun fromString(value: String): MultiAgentStrategy =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: SINGLE
    }
}
