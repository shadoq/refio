package pl.jclab.refio.core.models.api

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.db.TaskMode

/**
 * Request model for planning endpoint.
 */
data class PlanningRequest(
    val input: String,
    val contextRefs: List<ContextReference> = emptyList(),
    val model: String? = null,
    val provider: String? = null,
    val interactive: Boolean = true
)

/**
 * Response model for planning endpoint.
 */
data class PlanningResponse(
    val plan: String,
    val subtasks: List<SubtaskResponse>,
    val costs: PlanCost,
    val modelUsed: String,
    val providerUsed: String
)

/**
 * Subtask response (created subtask details).
 */
data class SubtaskResponse(
    val id: String,
    val taskId: String,
    val orderIndex: Int,
    val kind: String,
    val status: String,
    val approvalStatus: String,
    val requiresApproval: Boolean,
    val approvedByUser: Boolean,
    val description: String,
    val paramsJson: String?,
    val stepPlanJson: String?,
    val startedAt: Long?,
    val finishedAt: Long?,
    val errorCode: String?,
    val errorMessage: String?,
    val tokensIn: Int,
    val tokensOut: Int,
    val costUsd: Double,
    val model: String?,
    val provider: String?,
    val resultSummary: String? = null
)

/**
 * Plan cost breakdown.
 */
data class PlanCost(
    val tokensIn: Int,
    val tokensOut: Int,
    val usdEst: Double
)