package pl.jclab.refio.core.api

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
