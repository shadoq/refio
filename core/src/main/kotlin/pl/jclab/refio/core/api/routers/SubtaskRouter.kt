package pl.jclab.refio.core.api.routers

import pl.jclab.refio.core.api.GetSubtasksResponse
import pl.jclab.refio.core.api.Router
import pl.jclab.refio.core.api.SubtaskResponse
import pl.jclab.refio.core.api.UpdateSubtaskRequest
import pl.jclab.refio.core.db.ApprovalStatus
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("SubtaskRouter")

/**
 * Router for subtask management operations.
 * Handles subtask CRUD, approval, and ordering.
 *
 * @property subtaskRepository Subtask storage repository
 */
class SubtaskRouter(
    private val subtaskRepository: SubtaskRepository
) : Router {

    override suspend fun initialize() {
        logger.info { "[SubtaskRouter] Initialized" }
    }

    override suspend fun shutdown() {
        logger.info { "[SubtaskRouter] Shutting down" }
    }

    // ===== Subtask Query Operations =====

    /**
     * Get single subtask by ID.
     *
     * @param taskId Task ID
     * @param subtaskId Subtask ID
     * @return Subtask details
     * @throws IllegalArgumentException If subtask not found
     */
    fun getSubtask(taskId: String, subtaskId: String): SubtaskResponse {
        logger.info { "[SubtaskRouter] Getting subtask: taskId=$taskId, subtaskId=$subtaskId" }

        val subtask = subtaskRepository.findById(subtaskId)
            ?: throw IllegalArgumentException("Subtask not found: $subtaskId")

        // Verify taskId matches
        if (subtask.taskId != taskId) {
            throw IllegalArgumentException("Subtask $subtaskId does not belong to task $taskId")
        }

        return SubtaskResponse(
            id = subtask.id,
            taskId = subtask.taskId,
            orderIndex = subtask.orderIndex,
            kind = subtask.kind.name,
            status = subtask.status.name,
            approvalStatus = subtask.approvalStatus.name,
            requiresApproval = subtask.requiresApproval,
            approvedByUser = subtask.approvedAt != null,
            description = subtask.description,
            paramsJson = subtask.paramsJson,
            stepPlanJson = subtask.stepPlanJson,
            startedAt = subtask.startedAt,
            finishedAt = subtask.completedAt,
            errorCode = null,
            errorMessage = subtask.errorMessage,
            tokensIn = subtask.inputTokens,
            tokensOut = subtask.outputTokens,
            costUsd = subtask.costUsd,
            model = subtask.llmModel,
            provider = subtask.llmProvider
        )
    }

    /**
     * Get all subtasks for task.
     */
    fun getSubtasks(taskId: String): GetSubtasksResponse {
        logger.info { "[SubtaskRouter] Getting subtasks: taskId=$taskId" }

        val subtasks = subtaskRepository.findByTaskId(taskId)

        return GetSubtasksResponse(
            subtasks = subtasks.map { st ->
                SubtaskResponse(
                    id = st.id,
                    taskId = st.taskId,
                    orderIndex = st.orderIndex,
                    kind = st.kind.name,
                    status = st.status.name,
                    approvalStatus = st.approvalStatus.name,
                    requiresApproval = st.requiresApproval,
                    approvedByUser = st.approvedAt != null,  // Map approvedAt timestamp to boolean
                    description = st.description,
                    paramsJson = st.paramsJson,
                    stepPlanJson = st.stepPlanJson,
                    startedAt = st.startedAt,
                    finishedAt = st.completedAt,  // Map completedAt to finishedAt
                    errorCode = null,  // errorCode not present in Subtask model
                    errorMessage = st.errorMessage,
                    tokensIn = st.inputTokens,  // Map inputTokens to tokensIn
                    tokensOut = st.outputTokens,  // Map outputTokens to tokensOut
                    costUsd = st.costUsd,
                    model = st.llmModel,  // Map llmModel to model
                    provider = st.llmProvider  // Map llmProvider to provider
                )
            },
            count = subtasks.size
        )
    }

    // ===== Subtask Management Operations =====

    /**
     * Update subtask status or approval status.
     */
    fun updateSubtask(taskId: String, subtaskId: String, request: UpdateSubtaskRequest): SubtaskResponse {
        logger.info { "[SubtaskRouter] Updating subtask: taskId=$taskId, subtaskId=$subtaskId, status=${request.status}" }

        // Update status if provided
        val subtask = if (request.status != null) {
            subtaskRepository.updateStatus(subtaskId, request.status)
                ?: throw IllegalArgumentException("Subtask not found: $subtaskId")
        } else {
            subtaskRepository.findById(subtaskId)
                ?: throw IllegalArgumentException("Subtask not found: $subtaskId")
        }

        // Update approval status if provided
        val updated = if (request.approvalStatus != null) {
            subtaskRepository.updateApprovalStatus(subtaskId, request.approvalStatus)
                ?: throw IllegalArgumentException("Subtask not found: $subtaskId")
        } else {
            subtask
        }

        return SubtaskResponse(
            id = updated.id,
            taskId = updated.taskId,
            orderIndex = updated.orderIndex,
            kind = updated.kind.name,
            status = updated.status.name,
            approvalStatus = updated.approvalStatus.name,
            requiresApproval = updated.requiresApproval,
            approvedByUser = updated.approvedAt != null,
            description = updated.description,
            paramsJson = updated.paramsJson,
            stepPlanJson = updated.stepPlanJson,
            startedAt = updated.startedAt,
            finishedAt = updated.completedAt,
            errorCode = null,
            errorMessage = updated.errorMessage,
            tokensIn = updated.inputTokens,
            tokensOut = updated.outputTokens,
            costUsd = updated.costUsd,
            model = updated.llmModel,
            provider = updated.llmProvider
        )
    }

    /**
     * Approve subtask.
     */
    fun approveSubtask(taskId: String, subtaskId: String): SubtaskResponse {
        logger.info { "[SubtaskRouter] Approving subtask: subtaskId=$subtaskId" }

        val subtask = subtaskRepository.findById(subtaskId)
            ?: throw IllegalArgumentException("Subtask not found: $subtaskId")

        if (subtask.taskId != taskId) {
            throw IllegalArgumentException("Subtask $subtaskId does not belong to task $taskId")
        }

        subtaskRepository.updateApprovalStatus(subtaskId, ApprovalStatus.APPROVED)

        val updated = subtaskRepository.findById(subtaskId)!!

        return SubtaskResponse(
            id = updated.id,
            taskId = updated.taskId,
            orderIndex = updated.orderIndex,
            kind = updated.kind.name,
            status = updated.status.name,
            approvalStatus = updated.approvalStatus.name,
            requiresApproval = updated.requiresApproval,
            approvedByUser = updated.approvedAt != null,
            description = updated.description,
            paramsJson = updated.paramsJson,
            stepPlanJson = updated.stepPlanJson,
            startedAt = updated.startedAt,
            finishedAt = updated.completedAt,
            errorCode = null,
            errorMessage = updated.errorMessage,
            tokensIn = updated.inputTokens,
            tokensOut = updated.outputTokens,
            costUsd = updated.costUsd,
            model = updated.llmModel,
            provider = updated.llmProvider
        )
    }

    /**
     * Reject subtask.
     */
    fun rejectSubtask(taskId: String, subtaskId: String): SubtaskResponse {
        logger.info { "[SubtaskRouter] Rejecting subtask: subtaskId=$subtaskId" }

        val subtask = subtaskRepository.findById(subtaskId)
            ?: throw IllegalArgumentException("Subtask not found: $subtaskId")

        if (subtask.taskId != taskId) {
            throw IllegalArgumentException("Subtask $subtaskId does not belong to task $taskId")
        }

        subtaskRepository.updateApprovalStatus(subtaskId, ApprovalStatus.SKIPPED)

        val updated = subtaskRepository.findById(subtaskId)!!

        return SubtaskResponse(
            id = updated.id,
            taskId = updated.taskId,
            orderIndex = updated.orderIndex,
            kind = updated.kind.name,
            status = updated.status.name,
            approvalStatus = updated.approvalStatus.name,
            requiresApproval = updated.requiresApproval,
            approvedByUser = updated.approvedAt != null,
            description = updated.description,
            paramsJson = updated.paramsJson,
            stepPlanJson = updated.stepPlanJson,
            startedAt = updated.startedAt,
            finishedAt = updated.completedAt,
            errorCode = null,
            errorMessage = updated.errorMessage,
            tokensIn = updated.inputTokens,
            tokensOut = updated.outputTokens,
            costUsd = updated.costUsd,
            model = updated.llmModel,
            provider = updated.llmProvider
        )
    }

    /**
     * Delete subtask.
     */
    fun deleteSubtask(taskId: String, subtaskId: String): DeleteSubtaskResponse {
        logger.info { "[SubtaskRouter] Deleting subtask: subtaskId=$subtaskId" }

        val subtask = subtaskRepository.findById(subtaskId)
            ?: throw IllegalArgumentException("Subtask not found: $subtaskId")

        if (subtask.taskId != taskId) {
            throw IllegalArgumentException("Subtask $subtaskId does not belong to task $taskId")
        }

        subtaskRepository.delete(subtaskId)

        return DeleteSubtaskResponse(
            subtaskId = subtaskId,
            deleted = true
        )
    }

    /**
     * Delete pending subtasks.
     */
    fun deletePendingSubtasks(taskId: String): DeleteSubtasksResponse {
        logger.info { "[SubtaskRouter] Deleting pending subtasks: taskId=$taskId" }

        val count = subtaskRepository.deletePendingByTaskId(taskId)

        return DeleteSubtasksResponse(
            taskId = taskId,
            deletedCount = count
        )
    }

    /**
     * Delete all subtasks for a task.
     * Used to reset execution-related data after a "rewind conversation" operation.
     */
    fun deleteAllSubtasks(taskId: String): DeleteSubtasksResponse {
        logger.info { "[SubtaskRouter] Deleting all subtasks: taskId=$taskId" }
        val count = subtaskRepository.deleteByTaskId(taskId)
        return DeleteSubtasksResponse(
            taskId = taskId,
            deletedCount = count
        )
    }

    /**
     * Swap subtask order.
     */
    fun swapSubtaskOrder(taskId: String, subtaskId1: String, subtaskId2: String): SwapOrderResponse {
        logger.info { "[SubtaskRouter] Swapping subtask order: $subtaskId1 <-> $subtaskId2" }

        val subtask1 = subtaskRepository.findById(subtaskId1)
            ?: throw IllegalArgumentException("Subtask not found: $subtaskId1")
        val subtask2 = subtaskRepository.findById(subtaskId2)
            ?: throw IllegalArgumentException("Subtask not found: $subtaskId2")

        if (subtask1.taskId != taskId || subtask2.taskId != taskId) {
            throw IllegalArgumentException("Subtasks must belong to task $taskId")
        }

        val order1 = subtask1.orderIndex
        val order2 = subtask2.orderIndex

        subtaskRepository.updateOrderIndex(subtaskId1, order2)
        subtaskRepository.updateOrderIndex(subtaskId2, order1)

        return SwapOrderResponse(
            subtask1Id = subtaskId1,
            subtask2Id = subtaskId2,
            swapped = true
        )
    }
}

// ===== Response DTOs =====

data class DeleteSubtaskResponse(
    val subtaskId: String,
    val deleted: Boolean
)

data class DeleteSubtasksResponse(
    val taskId: String,
    val deletedCount: Int
)

data class SwapOrderResponse(
    val subtask1Id: String,
    val subtask2Id: String,
    val swapped: Boolean
)
