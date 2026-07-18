package pl.jclab.refio.core.agents

import pl.jclab.refio.core.db.TaskMode

/**
 * Specification for a single agent in a multi-agent session.
 * Parsed from YAML task definition or created programmatically.
 */
data class AgentSpec(
    val name: String,
    val profile: String? = null,
    val task: String,
    val mode: TaskMode = TaskMode.AGENT,
    val model: String? = null,
    val dependsOn: List<String> = emptyList()
)

/**
 * Result from a single agent execution.
 */
data class AgentResult(
    val agentName: String,
    val success: Boolean,
    val response: String,
    val tokensUsed: Long = 0,
    val costUsd: Double = 0.0,
    val durationMs: Long = 0,
    val error: String? = null,
    /** Absolute epoch-ms bounds of this agent's turn, so execution order can be reconstructed
     *  (the runner returns an unordered map). Null when not captured. */
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    /** Prompt/completion token split behind [tokensUsed], so run.json can report a real
     *  output-token figure instead of collapsing input and output into one number. */
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
)
