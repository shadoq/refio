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
