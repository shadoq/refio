package pl.jclab.refio.core.agents.events

/**
 * Sealed interface for all multi-agent events.
 *
 * Events flow through AgentEventBus and are persisted in agent_events table.
 * GUI subscribes to events for real-time visualization (DAG, interleaved chat).
 */
sealed interface AgentEvent {
    val id: String
    val sessionId: String
    val sourceAgentId: String
    val timestamp: Long
    val correlationId: String

    // ── LIFECYCLE — agent start/complete/fail ──

    data class AgentStarted(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val agentName: String,
        val profile: String?,
        val task: String,
        val model: String?,
        val dependsOn: List<String>
    ) : AgentEvent

    data class AgentCompleted(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val summary: String,
        val artifacts: List<Artifact>,
        val tokensUsed: Long,
        val costUsd: Double,
        val durationMs: Long
    ) : AgentEvent

    data class AgentFailed(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val error: String,
        val recoverable: Boolean
    ) : AgentEvent

    // ── DATA EXCHANGE — inter-agent communication ──

    data class DataRequest(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val targetAgentId: String?,
        val query: String,
        val context: Map<String, String> = emptyMap()
    ) : AgentEvent

    data class DataResponse(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val targetAgentId: String,
        val requestId: String,
        val response: String,
        val artifacts: List<Artifact> = emptyList()
    ) : AgentEvent

    // ── COORDINATION ──

    data class ArtifactProduced(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val artifact: Artifact
    ) : AgentEvent

    data class SpawnAgentRequest(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val requestedProfile: String,
        val task: String
    ) : AgentEvent

    data class AgentSpawned(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val spawnedAgentId: String,
        val requestId: String
    ) : AgentEvent

    // ── APPROVAL — user approval flow ──

    data class ApprovalRequired(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val action: String,
        val actionType: String,
        val risk: String,
        val details: Map<String, String>
    ) : AgentEvent

    data class ApprovalDecision(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val approvalId: String,
        val approved: Boolean,
        val reason: String?
    ) : AgentEvent

    // ── PROGRESS — GUI updates ──

    data class ProgressUpdate(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val phase: String,
        val message: String,
        val progress: Float?
    ) : AgentEvent

    data class StreamChunk(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val delta: String,
        val accumulated: String,
        val isComplete: Boolean
    ) : AgentEvent

    // ── TURN LIFECYCLE — per-iteration events for Session Trace panel ──

    /**
     * Emitted at the beginning of a single AgentTurnLoop iteration.
     * Used by Session Trace view to group nested spans (LLM call, tool calls) under a Turn node.
     */
    data class TurnStarted(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val iteration: Int,
        val maxIterations: Int,
        val mode: String
    ) : AgentEvent

    /**
     * Emitted after a Turn iteration finishes (either loops again or completes).
     * durationMs covers the full iteration including prompt build, LLM call and tool execution.
     */
    data class TurnEnded(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val iteration: Int,
        val durationMs: Long,
        val isFinal: Boolean
    ) : AgentEvent

    /**
     * Emitted after a single LLM generation completes.
     * Carries token usage, cost and timing so the trace panel can build a Generation span.
     */
    data class LLMCallCompleted(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val iteration: Int,
        val model: String,
        val provider: String?,
        val tokensIn: Int,
        val tokensOut: Int,
        val costUsd: Double,
        val durationMs: Long,
        val finishReason: String?
    ) : AgentEvent

    /**
     * Emitted after a single tool invocation completes.
     * Carries timing/success so the trace panel and tool analytics can aggregate stats.
     */
    data class ToolCalled(
        override val id: String,
        override val sessionId: String,
        override val sourceAgentId: String,
        override val timestamp: Long,
        override val correlationId: String,
        val iteration: Int,
        val toolName: String,
        val argumentsPreview: String,
        val durationMs: Long,
        val success: Boolean,
        val errorMessage: String?,
        val resultPreview: String
    ) : AgentEvent
}

/**
 * Artifact produced by an agent.
 */
data class Artifact(
    val type: String,    // FILE_CREATED, FILE_MODIFIED, ANALYSIS, SPECIFICATION, CODE_REVIEW, etc.
    val name: String,
    val content: String? = null,
    val path: String? = null,
    val metadata: Map<String, String> = emptyMap()
)
