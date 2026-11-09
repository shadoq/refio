package pl.jclab.refio.core.agents

import pl.jclab.refio.core.db.AgentInstanceStatus
import pl.jclab.refio.core.db.TaskMode

/**
 * Specification for a single agent in a multi-agent session.
 * Parsed from YAML task definition or created programmatically.
 */
data class AgentSpec(
    val name: String,
    /** Name of the subagent definition whose instructions and tool limits the agent runs with. */
    val profile: String? = null,
    val task: String,
    val mode: TaskMode = TaskMode.AGENT,
    val model: String? = null,
    val dependsOn: List<String> = emptyList(),
    /** Iteration ceiling of the agent's turn; null keeps the profile's or the default one. */
    val maxIterations: Int? = null,
    /** Wall-clock limit of the agent's turn in minutes; null keeps the configured one. */
    val maxMinutes: Int? = null,
    /**
     * Spending limit of the agent's turn. Not read from YAML: the runner sets it to what is left
     * of the run's shared budget when the agent starts.
     */
    val maxCostUsd: Double? = null,
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
    /** How the run ended. Callers that only know [success] get COMPLETED or FAILED. */
    val status: AgentInstanceStatus = if (success) AgentInstanceStatus.COMPLETED else AgentInstanceStatus.FAILED,
    /** Files the agent's turn changed, for the agents that build on its work. */
    val filesChanged: List<String> = emptyList(),
    /** The agent's own task, so its conversation can be read back. Null when no turn ran. */
    val taskId: String? = null,
)

/** What the rest of a run does when an agent does not complete its work. */
enum class OnFailure {
    /** Agents that have not started yet are skipped; agents already running finish their turn. */
    STOP,

    /** Agents that depend on it, directly or through others, are skipped; the rest go on. */
    SKIP,

    /** Everything runs; dependents see the failure in their dependency results. */
    CONTINUE,
}

/** Limits shared by every agent of one run. Null means no limit. */
data class WorkflowLimits(
    val onFailure: OnFailure = OnFailure.SKIP,
    val maxParallel: Int? = null,
    val maxMinutes: Int? = null,
    val maxCostUsd: Double? = null,
)
