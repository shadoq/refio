package pl.jclab.refio.core.api.routers

import pl.jclab.refio.core.api.Router
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.api.PlanningRequest
import pl.jclab.refio.core.api.PlanningResponse
import pl.jclab.refio.core.services.PlanningService
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("PlanningRouter")

/**
 * Router for planning-related operations.
 * Handles plan generation and validation for tasks.
 *
 * This router is responsible for:
 * - Creating execution plans from user requirements
 * - Breaking down high-level goals into executable subtasks
 * - Streaming plan generation progress
 *
 * @property planningService Plan generation and validation service
 * @property subtaskRepository Subtask storage repository
 * @property taskRepository Task management repository
 */
class PlanningRouter(
    private val planningService: PlanningService,
    private val subtaskRepository: SubtaskRepository,
    private val taskRepository: TaskRepository
) : Router {

    override suspend fun initialize() {
        logger.info { "[PlanningRouter] Initialized" }
    }

    override suspend fun shutdown() {
        logger.info { "[PlanningRouter] Shutting down" }
    }

    // ===== Planning Operations =====

    /**
     * Create an execution plan for a task.
     *
     * Analyzes user requirements and generates a structured plan with subtasks.
     * Supports both streaming and non-streaming modes.
     *
     * @param taskId Task ID to create plan for
     * @param request Planning request with user input and parameters
     * @param stream If true, onChunk callback will be called with progress
     * @param onChunk Optional callback for streaming updates to UI
     * @return Planning response with plan, steps, subtasks, and cost info
     * @throws IllegalArgumentException If task not found or mode is not PLAN/AGENT
     * @throws Exception On LLM API errors or planning failures
     */
    suspend fun plan(
        taskId: String,
        request: PlanningRequest,
        stream: Boolean = false,
        onChunk: StreamCallback? = null
    ): PlanningResponse {
        logger.info { "[PlanningRouter] plan: taskId=$taskId, stream=$stream" }
        return planningService.createPlan(taskId, request, stream, onChunk)
    }
}
