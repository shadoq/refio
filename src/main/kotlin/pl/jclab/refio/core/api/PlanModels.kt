package pl.jclab.refio.core.api

import pl.jclab.refio.core.db.Plan
import pl.jclab.refio.core.db.PlanStep

/**
 * Plan operation enum for iterative planning
 * Determines how LLM should interact with existing plan
 */
enum class PlanOperation {
    CREATE_OR_EXTEND,  // Create new plan or add steps to existing
    REPLACE,           // Replace all existing steps with new plan
    REFINE             // LLM analyzes and optimizes existing plan
}

// ============================================================
// Plan CRUD Models
// ============================================================

/**
 * Request to create a new plan
 */
data class CreatePlanRequest(
    val sessionId: String,
    val name: String,
    val description: String? = null
)

/**
 * Request to update plan metadata
 */
data class UpdatePlanRequest(
    val name: String? = null,
    val description: String? = null
)

// ============================================================
// Plan Step Management Models
// ============================================================

/**
 * Request to add a new step to a plan
 */
data class AddPlanStepRequest(
    val planId: String,
    val kind: String,
    val description: String,
    val paramsJson: String? = null,
    val insertAfterIndex: Int? = null,  // null = append to end
    val isWriteOp: Boolean = false
)

/**
 * Request to update an existing step
 */
data class UpdatePlanStepRequest(
    val stepId: String,
    val kind: String? = null,
    val description: String? = null,
    val paramsJson: String? = null,
    val isWriteOp: Boolean? = null
)

/**
 * Request to reorder plan steps
 */
data class ReorderPlanStepsRequest(
    val planId: String,
    val stepIds: List<String>  // Step IDs in desired order
)

// ============================================================
// Plan Execution Models
// ============================================================

/**
 * Request to execute a plan
 * Creates new AGENT session and copies plan steps to subtasks
 */
data class ExecutePlanRequest(
    val planId: String,
    val sessionName: String? = null,  // Name for new AGENT session
    val orchestrationEnabled: Boolean = false
)

/**
 * Response after executing a plan
 */
data class ExecutePlanResponse(
    val executionSessionId: String,  // ID of newly created AGENT session
    val executionSessionName: String,
    val subtasksCreated: Int,
    val planVersion: Int,
    val planId: String
)

// ============================================================
// Response Models
// ============================================================

/**
 * Complete plan response with steps
 */
data class PlanResponse(
    val id: String,
    val sessionId: String,
    val name: String,
    val description: String?,
    val status: String,  // DRAFT, READY, EXECUTING, EXECUTED
    val version: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val finalizedAt: Long?,
    val steps: List<PlanSpecStepResponse> = emptyList(),
    val statistics: PlanStatisticsResponse? = null
)

/**
 * Plan specification step response
 * (Different from PlanStepResponse in ApiModels which is for execution planning)
 */
data class PlanSpecStepResponse(
    val id: String,
    val planId: String,
    val orderIndex: Int,
    val kind: String,
    val description: String,
    val paramsJson: String?,
    val isWriteOp: Boolean,
    val createdBy: String,  // LLM or USER
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Plan statistics
 */
data class PlanStatisticsResponse(
    val totalSteps: Int,
    val writeSteps: Int,
    val readSteps: Int,
    val llmCreatedSteps: Int,
    val userCreatedSteps: Int,
    val executionCount: Int  // How many times this plan was executed
)

/**
 * Execution summary
 * Historical record of plan executions
 */
data class ExecutionSummaryResponse(
    val sessionId: String,
    val sessionName: String,
    val planVersion: Int,
    val status: String,
    val startedAt: Long,
    val completedAt: Long?,
    val subtasksTotal: Int,
    val subtasksSuccess: Int,
    val subtasksFailed: Int,
    val tokensUsed: Int,
    val costUsd: Double
)

// ============================================================
// Extension functions for converting DB models to API responses
// ============================================================

/**
 * Convert Plan entity to response DTO
 */
fun Plan.toResponse(steps: List<PlanStep> = emptyList()): PlanResponse {
    return PlanResponse(
        id = id,
        sessionId = sessionId,
        name = name,
        description = description,
        status = status.name,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
        finalizedAt = finalizedAt,
        steps = steps.map { it.toResponse() }
    )
}

/**
 * Convert PlanStep entity to response DTO
 */
fun PlanStep.toResponse(): PlanSpecStepResponse {
    return PlanSpecStepResponse(
        id = this.id,
        planId = this.planId,
        orderIndex = this.orderIndex,
        kind = this.kind,
        description = this.description,
        paramsJson = this.paramsJson,
        isWriteOp = this.isWriteOp,
        createdBy = this.createdBy.name,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt
    )
}
