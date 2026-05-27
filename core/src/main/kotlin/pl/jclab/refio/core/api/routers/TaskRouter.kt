package pl.jclab.refio.core.api.routers

import pl.jclab.refio.core.api.*
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("TaskRouter")

/**
 * Router for task management operations.
 * Handles task CRUD, status updates, and task queries.
 *
 * @property taskRepository Task storage repository
 */
class TaskRouter(
    private val taskRepository: TaskRepository,
    private val configService: ConfigService? = null,
    private val defaultProjectId: String? = null,
    private val defaultProjectPath: String? = null
) : Router {

    /**
     * Helper function to map Task entity to TaskResponse DTO.
     */
    private fun pl.jclab.refio.core.db.Task.toResponse(
        tokensInOverride: Int? = null,
        tokensOutOverride: Int? = null,
        costUsdOverride: Double? = null
    ): TaskResponse = TaskResponse(
        id = id,
        name = name,
        mode = mode.name,
        status = status.name,
        readOnly = readOnly,
        pinned = pinned,
        executionMode = executionMode.name,
        requiresPlanApproval = requiresPlanApproval,
        planApproved = planApproved,
        uiState = uiState,
        createdAt = createdAt,
        updatedAt = updatedAt,
        tokensIn = tokensInOverride ?: tokensIn,
        tokensOut = tokensOutOverride ?: tokensOut,
        costUsd = costUsdOverride ?: costUsd,
        rate = rate,
        projectId = projectId,
        projectPath = projectPath
    )

    override suspend fun initialize() {
        logger.info { "[TaskRouter] Initialized" }
    }

    override suspend fun shutdown() {
        logger.info { "[TaskRouter] Shutting down" }
    }

    // ===== Task Management Operations =====

    /**
     * Create a new task.
     */
    fun createTask(request: CreateTaskRequest): TaskResponse {
        logger.info { "[TaskRouter] Creating task: name=${request.name}, mode=${request.mode}" }

        val effectiveProjectId = request.projectId.ifBlank { defaultProjectId ?: LEGACY_PROJECT_ID }
        val effectiveProjectPath = request.projectPath.ifBlank { defaultProjectPath ?: LEGACY_PROJECT_PATH }
        val readOnly = request.readOnly ?: (request.mode == TaskMode.PLAN || (configService?.getTyped(ConfigKeys.READ_ONLY_MODE) ?: false))
        val requiresPlanApproval = request.requiresPlanApproval ?: false

        val task = taskRepository.create(
            name = request.name,
            mode = request.mode,
            projectId = effectiveProjectId,
            projectPath = effectiveProjectPath,
            readOnly = readOnly,
            requiresPlanApproval = requiresPlanApproval,
            planApproved = false
        )

        return task.toResponse()
    }

    /**
     * List all tasks with aggregated stats.
     */
    fun listTasks(): ListTasksResponse {
        logger.info { "[TaskRouter] Listing all tasks with stats" }

        val tasksWithStats = taskRepository.listTasksWithStats(limit = 100)
        val tasks = tasksWithStats.map { tws ->
            tws.task.toResponse(
                tokensInOverride = tws.tokensIn,
                tokensOutOverride = tws.tokensOut,
                costUsdOverride = tws.costUsd
            )
        }

        return ListTasksResponse(tasks = tasks, count = tasks.size)
    }

    /**
     * Get task by ID.
     */
    fun getTask(taskId: String): TaskResponse? {
        return taskRepository.findById(taskId)?.toResponse()
    }

    /**
     * Update task (mode, name, status, etc.)
     */
    fun updateTask(taskId: String, request: UpdateTaskRequest): TaskResponse {
        logger.info { "[TaskRouter] Updating task: id=$taskId" }

        val updatedTask = taskRepository.update(
            id = taskId,
            name = request.name,
            mode = request.mode,
            status = request.status,
            readOnly = request.readOnly,
            pinned = request.pinned,
            executionMode = request.executionMode,
            requiresPlanApproval = request.requiresPlanApproval,
            planApproved = request.planApproved,
            uiState = request.uiState,
            rate = request.rate
        ) ?: throw IllegalArgumentException("Task not found: $taskId")

        return updatedTask.toResponse()
    }

    /**
     * Delete task.
     */
    fun deleteTask(taskId: String): Boolean {
        logger.info { "[TaskRouter] Deleting task: taskId=$taskId" }
        taskRepository.delete(taskId)
        return true
    }

    /**
     * Get all tasks for project.
     */
    fun getTasksForProject(projectId: String): List<TaskResponse> {
        return taskRepository.getForProject(projectId).map { it.toResponse() }
    }

    /**
     * Get last session for project.
     */
    fun getLastSessionForProject(projectId: String): TaskResponse? {
        return taskRepository.getLastForProject(projectId)?.toResponse()
    }

    // ===== Goal (`/goal`) Operations =====

    /**
     * Set the completion condition for a task. Pass `null` to clear.
     *
     * When set, `NextSpeakerJudgeGuardian` switches to goal-aware judging: instead of
     * deciding "is the turn finished?" it decides "has THIS condition been demonstrably
     * met based on the transcript?". Condition is capped at 4000 chars (Claude Code parity).
     *
     * @return true on success, false when task not found.
     * @throws IllegalArgumentException if the condition exceeds 4000 chars.
     */
    fun setGoal(taskId: String, condition: String?): Boolean {
        val trimmed = condition?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed != null && trimmed.length > MAX_GOAL_LENGTH) {
            throw IllegalArgumentException(
                "Goal condition exceeds $MAX_GOAL_LENGTH characters (was ${trimmed.length}). " +
                    "Shorten the condition or split into smaller goals."
            )
        }
        logger.info { "[TaskRouter] ${if (trimmed != null) "Setting" else "Clearing"} goal for task $taskId" }
        return taskRepository.setCompletionCondition(taskId, trimmed)
    }

    /**
     * Get the active completion condition for a task, or null when none is set.
     */
    fun getGoal(taskId: String): String? = taskRepository.getCompletionCondition(taskId)

    /**
     * Clear the active completion condition. Equivalent to `setGoal(taskId, null)`.
     */
    fun clearGoal(taskId: String): Boolean {
        logger.info { "[TaskRouter] Clearing goal for task $taskId" }
        return taskRepository.setCompletionCondition(taskId, null)
    }

    companion object {
        /** Max length of a `/goal` completion condition. Matches Claude Code's 4000-char limit. */
        const val MAX_GOAL_LENGTH = 4000
    }

    /**
     * Health check.
     */
    fun health(): HealthResponse {
        return HealthResponse(
            status = "ok",
            version = "1.0-SNAPSHOT",
            timestamp = System.currentTimeMillis(),
            message = "Core is healthy"
        )
    }
}
