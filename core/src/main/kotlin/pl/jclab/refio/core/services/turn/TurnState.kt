package pl.jclab.refio.core.services.turn

import java.time.Instant

/**
 * Observable state of a single turn execution.
 * UI observes this via StateFlow to show what the agent is doing right now.
 */
enum class TurnPhase {
    IDLE,
    BUILDING_PROMPT,
    CALLING_MODEL,
    EXECUTING_TOOLS,
    WAITING_FOR_PERMISSION,
    WAITING_FOR_USER,
    FINALIZING,
    COMPLETED,
    FAILED
}

/**
 * Snapshot of current turn execution state.
 * Emitted via StateFlow on every phase transition.
 */
data class TurnStateSnapshot(
    val phase: TurnPhase = TurnPhase.IDLE,
    val taskId: String? = null,
    val iteration: Int = 0,
    val maxIterations: Int = 0,
    val activeToolName: String? = null,
    val activeToolCount: Int = 0,
    val tokensUsed: Int = 0,
    val lastUpdated: Instant = Instant.now()
)
