package pl.jclab.refio.core.api.routers

import pl.jclab.refio.core.api.Router
import pl.jclab.refio.core.api.TaskResponse
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("TaskRouter")

/**
 * Router for task management operations.
 * Handles task CRUD, status updates, and task queries.
 *
 * @property taskRepository Task storage repository
 */
class TaskRouter(
    private val taskRepository: TaskRepository
) : Router {

    /**
     * Helper function to map Task entity to TaskResponse DTO.
     */
    private fun pl.jclab.refio.core.db.Task.toResponse(): TaskResponse = TaskResponse(
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
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        costUsd = costUsd,
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
     * Create new task.
     */
    fun createTask(
        name: String,
        mode: TaskMode,
        projectId: String?,
        projectPath: String?
    ): TaskResponse {
        logger.info { "[TaskRouter] Creating task: name=$name, mode=$mode, projectId=$projectId" }

        val task = taskRepository.create(
            name = name,
            mode = mode,
            projectId = projectId ?: "",
            projectPath = projectPath ?: ""
        )

        return task.toResponse()
    }

    /**
     * Get task by ID.
     */
    fun getTask(taskId: String): TaskResponse? {
        logger.info { "[TaskRouter] Getting task: taskId=$taskId" }
        return taskRepository.findById(taskId)?.toResponse()
    }

    /**
     * Update task.
     */
    fun updateTask(taskId: String, name: String?, status: TaskStatus?): TaskResponse {
        logger.info { "[TaskRouter] Updating task: taskId=$taskId, name=$name, status=$status" }

        if (taskRepository.findById(taskId) == null) {
            throw IllegalArgumentException("Task not found: $taskId")
        }

        taskRepository.update(
            id = taskId,
            name = name,
            status = status
        )

        return taskRepository.findById(taskId)!!.toResponse()
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
        logger.info { "[TaskRouter] Listing tasks for project $projectId" }
        return taskRepository.getForProject(projectId).map { it.toResponse() }
    }

    /**
     * Get last session for project.
     */
    fun getLastSessionForProject(projectId: String): TaskResponse? {
        logger.info { "[TaskRouter] Fetching last session for project $projectId" }
        return taskRepository.getLastForProject(projectId)?.toResponse()
    }
}
