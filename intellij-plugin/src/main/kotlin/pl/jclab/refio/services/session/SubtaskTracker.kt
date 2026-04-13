package pl.jclab.refio.services.session

import com.intellij.openapi.project.Project
import pl.jclab.refio.api.CoreApiClient
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.SubtaskDto
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.api.ExecuteStepResponse
import pl.jclab.refio.core.api.UpdateSubtaskRequest
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.services.project.SafeVfsAccess
import java.util.UUID

class SubtaskTracker(
    private val project: Project,
    private val projectRouter: CoreApiRouter,
    private val coreApiClient: CoreApiClient,
    private val stateManager: SessionStateManager,
    private val loadMessages: suspend () -> Unit,
    private val executeCurrentStep: suspend (String) -> ExecuteStepResponse?,
    private val showApprovalMessageForNextSubtask: suspend () -> Unit
) {

    private val logger = dualLogger("SubtaskTracker")

    fun updateSubtasks(subtasks: List<SubtaskDto>) {
        stateManager.setSubtasks(subtasks)
        logger.debug { "Updated subtasks: ${subtasks.size} items" }
    }

    suspend fun loadSubtasks() {
        val currentSession = stateManager.getActiveSession() ?: return

        try {
            logger.info { "[SUBTASK] loadSubtasks start: taskId=${currentSession.id}" }
            val response = projectRouter.subtaskRouter.getSubtasks(currentSession.id)
            logger.info { "[SUBTASK] loadSubtasks response: taskId=${currentSession.id}, count=${response.subtasks.size}" }

            val subtasks = response.subtasks.map { coreSubtask ->
                SubtaskDto(
                    id = coreSubtask.id,
                    taskId = coreSubtask.taskId,
                    orderIndex = coreSubtask.orderIndex,
                    kind = coreSubtask.kind,
                    status = coreSubtask.status,
                    approvalStatus = coreSubtask.approvalStatus,
                    requiresApproval = coreSubtask.requiresApproval,
                    approvedByUser = coreSubtask.approvedByUser,
                    description = coreSubtask.description,
                    paramsJson = coreSubtask.paramsJson,
                    stepPlanJson = coreSubtask.stepPlanJson,
                    summary = coreSubtask.summary,
                    result = coreSubtask.result,
                    startedAt = coreSubtask.startedAt,
                    finishedAt = coreSubtask.finishedAt,
                    errorCode = coreSubtask.errorCode,
                    errorMessage = coreSubtask.errorMessage,
                    tokensIn = coreSubtask.tokensIn,
                    tokensOut = coreSubtask.tokensOut,
                    costUsd = coreSubtask.costUsd,
                    latencyMs = coreSubtask.latencyMs,
                    model = coreSubtask.model,
                    provider = coreSubtask.provider,
                    resultSummary = coreSubtask.resultSummary,
                    createdAt = coreSubtask.createdAt,
                    updatedAt = coreSubtask.updatedAt,
                    completedAt = coreSubtask.completedAt
                )
            }

            val currentSubtasks = stateManager.getSubtasks()
            if (!areSubtasksEqual(currentSubtasks, subtasks)) {
                stateManager.setSubtasks(subtasks)
                logger.info { "Loaded ${subtasks.size} subtasks for session ${currentSession.id}" }
            } else {
                logger.debug { "Subtasks unchanged, skipping UI update (${subtasks.size} subtasks)" }
            }
        } catch (e: Exception) {
            logger.warn { "Failed to load subtasks for session ${currentSession.id}: ${e.message}" }
            stateManager.setSubtasks(emptyList())
        }
    }

    suspend fun approveSubtask(subtaskId: String) {
        val currentSession = stateManager.getActiveSession() ?: return
        try {
            logger.info { "[INTERACTIVE] User approved subtask: $subtaskId" }
            projectRouter.subtaskRouter.approveSubtask(
                taskId = currentSession.id,
                subtaskId = subtaskId
            )
            logger.info { "[INTERACTIVE] Subtask approved: $subtaskId" }
            loadSubtasks()

            if (currentSession.executionMode == pl.jclab.refio.api.models.ExecutionMode.INTERACTIVE) {
                logger.info { "[INTERACTIVE] Executing approved subtask: $subtaskId" }
                val executeResponse = executeCurrentStep(subtaskId)
                if (executeResponse != null) {
                    logger.info {
                        "[INTERACTIVE] Subtask executed: status=${executeResponse.status}, " +
                            "summary=${executeResponse.summary.take(100)}"
                    }
                } else {
                    logger.error { "[INTERACTIVE] Failed to execute subtask: $subtaskId" }
                }
                showApprovalMessageForNextSubtask()
            }
        } catch (e: Exception) {
            logger.error(e) { "[INTERACTIVE] Failed to approve subtask" }
        }
    }

    suspend fun skipSubtask(subtaskId: String) {
        val currentSession = stateManager.getActiveSession() ?: return
        try {
            logger.info { "Skipping subtask: $subtaskId" }
            projectRouter.subtaskRouter.rejectSubtask(
                taskId = currentSession.id,
                subtaskId = subtaskId
            )
            logger.info { "Subtask skipped: $subtaskId" }
            loadSubtasks()

            if (currentSession.executionMode == pl.jclab.refio.api.models.ExecutionMode.INTERACTIVE) {
                showApprovalMessageForNextSubtask()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to skip subtask" }
        }
    }

    suspend fun moveStepUp(subtaskId: String) {
        val currentSession = stateManager.getActiveSession() ?: return
        try {
            logger.info { "Moving subtask up: $subtaskId" }
            val sortedSubtasks = stateManager.getSubtasks().sortedBy { it.orderIndex }
            val currentIndex = sortedSubtasks.indexOfFirst { it.id == subtaskId }
            if (currentIndex <= 0) {
                logger.warn { "Cannot move first subtask up" }
                return
            }

            val currentSubtask = sortedSubtasks[currentIndex]
            val previousSubtask = sortedSubtasks[currentIndex - 1]

            projectRouter.subtaskRouter.swapSubtaskOrder(
                taskId = currentSession.id,
                subtaskId1 = currentSubtask.id,
                subtaskId2 = previousSubtask.id
            )

            logger.info { "Moved subtask up: $subtaskId" }
            loadSubtasks()
        } catch (e: Exception) {
            logger.error(e) { "Failed to move subtask up" }
        }
    }

    suspend fun moveStepDown(subtaskId: String) {
        val currentSession = stateManager.getActiveSession() ?: return
        try {
            logger.info { "Moving subtask down: $subtaskId" }
            val sortedSubtasks = stateManager.getSubtasks().sortedBy { it.orderIndex }
            val currentIndex = sortedSubtasks.indexOfFirst { it.id == subtaskId }
            if (currentIndex < 0 || currentIndex >= sortedSubtasks.size - 1) {
                logger.warn { "Cannot move last subtask down" }
                return
            }

            val currentSubtask = sortedSubtasks[currentIndex]
            val nextSubtask = sortedSubtasks[currentIndex + 1]

            projectRouter.subtaskRouter.swapSubtaskOrder(
                taskId = currentSession.id,
                subtaskId1 = currentSubtask.id,
                subtaskId2 = nextSubtask.id
            )

            logger.info { "Moved subtask down: $subtaskId" }
            loadSubtasks()
        } catch (e: Exception) {
            logger.error(e) { "Failed to move subtask down" }
        }
    }

    suspend fun deleteStep(subtaskId: String) {
        val currentSession = stateManager.getActiveSession() ?: return
        try {
            logger.info { "Deleting subtask: $subtaskId" }
            val subtask = stateManager.getSubtasks().find { it.id == subtaskId }
            if (subtask == null) {
                logger.error { "Subtask not found: $subtaskId" }
                return
            }

            if (subtask.status !in listOf("PENDING", "PLANNED", "NEW")) {
                logger.warn { "Cannot delete subtask with status: ${subtask.status}" }
                return
            }

            val result = projectRouter.subtaskRouter.deleteSubtask(currentSession.id, subtaskId)
            if (!result.deleted) {
                logger.error { "Failed to delete subtask: $subtaskId" }
                return
            }

            logger.info { "Deleted subtask: $subtaskId" }
            loadSubtasks()
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete subtask" }
        }
    }

    suspend fun executeSubtaskById(subtaskId: String) {
        val currentSession = stateManager.getActiveSession() ?: run {
            logger.warn { "No active session for executeSubtaskById" }
            return
        }

        try {
            logger.info { "[SUBTASK] executeSubtaskById start: taskId=${currentSession.id}, subtaskId=$subtaskId" }

            val subtask = stateManager.getSubtasks().find { it.id == subtaskId }
            if (subtask == null) {
                logger.error { "Subtask not found: $subtaskId" }
                return
            }

            if (subtask.status == "PENDING") {
                logger.info { "[SUBTASK] Preparing PENDING subtask: taskId=${currentSession.id}, subtaskId=$subtaskId" }
                coreApiClient.prepareStep(currentSession.id, subtaskId)
                loadSubtasks()
            }

            val executeResponse = executeCurrentStep(subtaskId)

            if (executeResponse != null) {
                logger.info {
                    "[SUBTASK] Subtask executed: taskId=${currentSession.id}, subtaskId=$subtaskId, " +
                        "status=${executeResponse.status}, durationMs=${executeResponse.durationMs}ms"
                }
                SafeVfsAccess.refreshProjectRoot(project, logger)
            } else {
                logger.error { "Failed to execute subtask: $subtaskId" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to execute subtask by ID" }

            val errorMessage = Message(
                id = UUID.randomUUID().toString(),
                taskId = currentSession.id,
                role = "system",
                content = "Error executing step: ${e.message}",
                createdAt = System.currentTimeMillis()
            )
            stateManager.appendMessage(errorMessage)
        }
    }

    suspend fun cancelAllPendingSteps() {
        val currentSession = stateManager.getActiveSession() ?: run {
            logger.warn { "No active session for cancelAllPendingSteps" }
            return
        }

        logger.info { "[SUBTASK] cancelAllPendingSteps: taskId=${currentSession.id}" }

        try {
            val deleteResponse = projectRouter.subtaskRouter.deletePendingSubtasks(currentSession.id)
            logger.info { "Deleted ${deleteResponse.deletedCount} pending subtasks" }

            loadSubtasks()

            val confirmMessage = Message(
                id = UUID.randomUUID().toString(),
                taskId = currentSession.id,
                role = "system",
                content = "Cancelled ${deleteResponse.deletedCount} pending steps.",
                createdAt = System.currentTimeMillis()
            )
            stateManager.appendMessage(confirmMessage)
        } catch (e: Exception) {
            logger.error(e) { "Failed to cancel pending steps" }

            val errorMessage = Message(
                id = UUID.randomUUID().toString(),
                taskId = currentSession.id,
                role = "system",
                content = "Error cancelling steps: ${e.message}",
                createdAt = System.currentTimeMillis()
            )
            stateManager.appendMessage(errorMessage)
        }
    }

    suspend fun prepareNextStep(): pl.jclab.refio.core.api.PlanStepResponse? {
        val currentSession = stateManager.getActiveSession()
            ?: throw IllegalStateException("No active session")

        logger.info {
            "[PREPARE] Looking for PENDING subtask: taskId=${currentSession.id}, " +
                "total=${stateManager.getSubtasks().size}"
        }
        stateManager.getSubtasks().forEachIndexed { index, subtask ->
            logger.info {
                "[PREPARE] Subtask[$index]: id=${subtask.id}, status=${subtask.status}, " +
                    "desc=${subtask.description?.take(50)}"
            }
        }

        var pendingSubtask = stateManager.getSubtasks().find { it.status == "PENDING" }
        if (pendingSubtask == null) {
            logger.info {
                "[PREPARE] No PENDING subtasks found. Available statuses: " +
                    "${stateManager.getSubtasks().map { it.status }.distinct()}"
            }

            val firstNewSubtask = stateManager.getSubtasks().find { it.status == "NEW" }
            if (firstNewSubtask != null) {
                logger.info { "[PREPARE] Transitioning first NEW subtask to PENDING: ${firstNewSubtask.id}" }
                try {
                    val updateRequest = UpdateSubtaskRequest(
                        status = pl.jclab.refio.core.db.TaskStatus.PENDING
                    )
                    projectRouter.subtaskRouter.updateSubtask(
                        taskId = currentSession.id,
                        subtaskId = firstNewSubtask.id,
                        request = updateRequest
                    )
                    loadSubtasks()
                    pendingSubtask = stateManager.getSubtasks().find { it.status == "PENDING" }
                    if (pendingSubtask == null) {
                        logger.error { "[PREPARE] Failed to transition subtask to PENDING" }
                        return null
                    }
                } catch (e: Exception) {
                    logger.error(e) { "[PREPARE] Failed to transition subtask" }
                    return null
                }
            } else {
                logger.warn { "[PREPARE] No NEW or PENDING subtasks found - execution completed or no subtasks" }
                return null
            }
        }

        logger.info { "[PREPARE] Found PENDING subtask: ${pendingSubtask.id} (${pendingSubtask.description})" }

        val prepareResponse = coreApiClient.prepareStep(currentSession.id, pendingSubtask.id)
        val tools = prepareResponse.tools.joinToString(", ") { it.name }
        logger.info {
            "[PREPARE] Prepared step: taskId=${currentSession.id}, subtaskId=${pendingSubtask.id}, tools=$tools"
        }

        loadSubtasks()
        return prepareResponse
    }

    private fun areSubtasksEqual(current: List<SubtaskDto>, new: List<SubtaskDto>): Boolean {
        if (current.size != new.size) return false
        return current.zip(new).all { (a, b) ->
            a.id == b.id &&
                a.orderIndex == b.orderIndex &&
                a.status == b.status &&
                a.approvalStatus == b.approvalStatus &&
                a.requiresApproval == b.requiresApproval &&
                a.approvedByUser == b.approvedByUser &&
                a.description == b.description &&
                a.paramsJson == b.paramsJson &&
                a.stepPlanJson == b.stepPlanJson &&
                a.startedAt == b.startedAt &&
                a.finishedAt == b.finishedAt &&
                a.errorCode == b.errorCode &&
                a.errorMessage == b.errorMessage &&
                a.tokensIn == b.tokensIn &&
                a.tokensOut == b.tokensOut &&
                a.costUsd == b.costUsd &&
                a.model == b.model &&
                a.provider == b.provider &&
                a.resultSummary == b.resultSummary
        }
    }
}
