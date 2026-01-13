package pl.jclab.refio.core.api.routers

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.api.*
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.db.repositories.*
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("PlanRouter")
private val gson = Gson()

/**
 * Router for plan management operations
 * Handles CRUD for plans and plan steps
 * Separate from PlanningRouter (which uses LLM to generate plans)
 */
class PlanRouter(
    private val planRepository: PlanRepository,
    private val planStepRepository: PlanStepRepository,
    private val taskRepository: TaskRepository,
    private val subtaskRepository: SubtaskRepository
) {

    // ============================================================
    // Plan CRUD
    // ============================================================

    /**
     * Get plan for a session (latest version)
     */
    suspend fun getPlan(sessionId: String): PlanResponse? = withContext(Dispatchers.IO) {
        val plan = planRepository.findBySessionId(sessionId) ?: return@withContext null
        val steps = planStepRepository.findByPlanId(plan.id)
        val statistics = planStepRepository.getStatistics(plan.id)
        val executionCount = planRepository.getExecutionCount(plan.id)

        plan.toResponse(steps).copy(
            statistics = PlanStatisticsResponse(
                totalSteps = statistics.totalSteps,
                writeSteps = statistics.writeSteps,
                readSteps = statistics.readSteps,
                llmCreatedSteps = statistics.llmCreated,
                userCreatedSteps = statistics.userCreated,
                executionCount = executionCount
            )
        )
    }

    /**
     * Get plan by ID
     */
    suspend fun getPlanById(planId: String): PlanResponse? = withContext(Dispatchers.IO) {
        val plan = planRepository.findById(planId) ?: return@withContext null
        val steps = planStepRepository.findByPlanId(plan.id)
        plan.toResponse(steps)
    }

    /**
     * Get all plans for a session (all versions)
     */
    suspend fun getAllPlans(sessionId: String): List<PlanResponse> = withContext(Dispatchers.IO) {
        planRepository.findAllBySessionId(sessionId).map { plan ->
            val steps = planStepRepository.findByPlanId(plan.id)
            plan.toResponse(steps)
        }
    }

    /**
     * Delete all plans (all versions) for a session.
     * Used to reset planning state after a "rewind conversation" operation.
     *
     * @return Number of deleted plans
     */
    suspend fun deleteAllPlansForSession(sessionId: String): Int = withContext(Dispatchers.IO) {
        val plans = planRepository.findAllBySessionId(sessionId)
        plans.forEach { plan ->
            planRepository.delete(plan.id)
        }
        plans.size
    }

    /**
     * Get plan steps
     */
    suspend fun getPlanSteps(planId: String): List<PlanSpecStepResponse> = withContext(Dispatchers.IO) {
        planStepRepository.findByPlanId(planId).map { it.toResponse() }
    }

    /**
     * Create a new plan
     */
    suspend fun createPlan(request: CreatePlanRequest): PlanResponse = withContext(Dispatchers.IO) {
        logger.info { "Creating plan for session ${request.sessionId}: ${request.name}" }

        val plan = planRepository.create(
            sessionId = request.sessionId,
            name = request.name,
            description = request.description
        )

        plan.toResponse()
    }

    /**
     * Update plan metadata
     */
    suspend fun updatePlan(planId: String, request: UpdatePlanRequest): PlanResponse = withContext(Dispatchers.IO) {
        logger.info { "Updating plan $planId" }

        val plan = planRepository.update(
            id = planId,
            name = request.name,
            description = request.description
        )

        val steps = planStepRepository.findByPlanId(plan.id)
        plan.toResponse(steps)
    }

    // ============================================================
    // Plan Step Management
    // ============================================================

    /**
     * Add step to plan
     */
    suspend fun addStep(request: AddPlanStepRequest): PlanSpecStepResponse = withContext(Dispatchers.IO) {
        logger.info { "Adding step to plan ${request.planId}: ${request.kind}" }

        // Check if plan is editable
        if (!planRepository.isEditable(request.planId)) {
            throw IllegalStateException("Cannot modify plan during execution")
        }

        val step = if (request.insertAfterIndex != null) {
            // Insert at specific position with shift
            planStepRepository.createWithShift(
                planId = request.planId,
                insertAt = request.insertAfterIndex + 1,
                kind = request.kind,
                description = request.description,
                paramsJson = request.paramsJson,
                isWriteOp = request.isWriteOp,
                createdBy = StepCreator.USER
            )
        } else {
            // Append to end
            val maxIndex = planStepRepository.getMaxOrderIndex(request.planId)
            planStepRepository.create(
                planId = request.planId,
                orderIndex = maxIndex + 1,
                kind = request.kind,
                description = request.description,
                paramsJson = request.paramsJson,
                isWriteOp = request.isWriteOp,
                createdBy = StepCreator.USER
            )
        }

        // Increment plan version
        planRepository.incrementVersion(request.planId)

        step.toResponse()
    }

    /**
     * Update plan step
     */
    suspend fun updateStep(request: UpdatePlanStepRequest): PlanSpecStepResponse = withContext(Dispatchers.IO) {
        logger.info { "Updating step ${request.stepId}" }

        val step = planStepRepository.findById(request.stepId)
            ?: throw IllegalArgumentException("Step not found: ${request.stepId}")

        // Check if plan is editable
        if (!planRepository.isEditable(step.planId)) {
            throw IllegalStateException("Cannot modify plan during execution")
        }

        val updated = planStepRepository.update(
            id = request.stepId,
            kind = request.kind,
            description = request.description,
            paramsJson = request.paramsJson,
            isWriteOp = request.isWriteOp
        )

        // Increment plan version
        planRepository.incrementVersion(step.planId)

        updated.toResponse()
    }

    /**
     * Delete plan step
     */
    suspend fun deleteStep(stepId: String) = withContext(Dispatchers.IO) {
        logger.info { "Deleting step $stepId" }

        val step = planStepRepository.findById(stepId)
            ?: throw IllegalArgumentException("Step not found: $stepId")

        // Check if plan is editable
        if (!planRepository.isEditable(step.planId)) {
            throw IllegalStateException("Cannot modify plan during execution")
        }

        planStepRepository.delete(stepId)

        // Increment plan version
        planRepository.incrementVersion(step.planId)
    }

    /**
     * Reorder plan steps
     */
    suspend fun reorderSteps(request: ReorderPlanStepsRequest) = withContext(Dispatchers.IO) {
        logger.info { "Reordering ${request.stepIds.size} steps for plan ${request.planId}" }

        // Check if plan is editable
        if (!planRepository.isEditable(request.planId)) {
            throw IllegalStateException("Cannot modify plan during execution")
        }

        planStepRepository.reorder(request.planId, request.stepIds)

        // Increment plan version
        planRepository.incrementVersion(request.planId)
    }

    // ============================================================
    // Plan Lifecycle
    // ============================================================

    /**
     * Finalize plan (DRAFT → READY)
     */
    suspend fun finalizePlan(planId: String): PlanResponse = withContext(Dispatchers.IO) {
        logger.info { "Finalizing plan $planId" }

        val plan = planRepository.updateStatus(planId, PlanStatus.READY)
        val steps = planStepRepository.findByPlanId(plan.id)

        plan.toResponse(steps)
    }

    /**
     * Reopen plan for editing (READY/EXECUTED → DRAFT)
     * Creates new version
     */
    suspend fun reopenPlan(planId: String): PlanResponse = withContext(Dispatchers.IO) {
        logger.info { "Reopening plan $planId for editing" }

        val plan = planRepository.updateStatus(planId, PlanStatus.DRAFT)
        val steps = planStepRepository.findByPlanId(plan.id)

        plan.toResponse(steps)
    }

    // ============================================================
    // Plan Execution
    // ============================================================

    /**
     * Execute plan - create AGENT session and copy plan steps to subtasks
     * This is the SNAPSHOT operation that creates execution from specification
     */
    suspend fun executePlan(request: ExecutePlanRequest): ExecutePlanResponse = withContext(Dispatchers.IO) {
        logger.info { "Executing plan ${request.planId}" }

        val plan = planRepository.findById(request.planId)
            ?: throw IllegalArgumentException("Plan not found: ${request.planId}")

        if (plan.status == PlanStatus.EXECUTING) {
            throw IllegalStateException("Plan is already executing")
        }

        // Get original session to inherit settings
        val originalSession = taskRepository.findById(plan.sessionId)
            ?: throw IllegalStateException("Original session not found: ${plan.sessionId}")

        // Create new AGENT session
        val agentSessionName = request.sessionName
            ?: "${plan.name} - Execution v${plan.version}"

        val agentSession = taskRepository.create(
            name = agentSessionName,
            mode = TaskMode.AGENT,
            projectId = originalSession.projectId,
            projectPath = originalSession.projectPath,
            executionMode = if (request.orchestrationEnabled) ExecutionMode.AUTO else ExecutionMode.INTERACTIVE,
            sourcePlanId = plan.id,
            planVersion = plan.version,
            uiState = originalSession.uiState  // Inherit UI settings
        )

        // Copy PlanSteps → Subtasks (SNAPSHOT)
        val planSteps = planStepRepository.findByPlanId(plan.id)
        planSteps.forEach { step ->
            // Map tool name to SubtaskKind
            val subtaskKind = mapToolNameToSubtaskKind(step.kind)

            subtaskRepository.create(
                taskId = agentSession.id,
                orderIndex = step.orderIndex,
                kind = subtaskKind,
                description = step.description,
                paramsJson = step.paramsJson,
                requiresApproval = !request.orchestrationEnabled,
                status = TaskStatus.PENDING
            )
        }

        // Update plan status to EXECUTING
        planRepository.updateStatus(plan.id, PlanStatus.EXECUTING)

        logger.info { "Created execution session ${agentSession.id} with ${planSteps.size} subtasks" }

        ExecutePlanResponse(
            executionSessionId = agentSession.id,
            executionSessionName = agentSession.name,
            subtasksCreated = planSteps.size,
            planVersion = plan.version,
            planId = plan.id
        )
    }

    /**
     * Get execution history for a plan
     */
    suspend fun getExecutionHistory(planId: String): List<ExecutionSummaryResponse> = withContext(Dispatchers.IO) {
        val executions = taskRepository.findAll(sourcePlanId = planId)

        executions.map { task ->
            val subtasks = subtaskRepository.findByTaskId(task.id)

            ExecutionSummaryResponse(
                sessionId = task.id,
                sessionName = task.name,
                planVersion = task.planVersion ?: 0,
                status = task.status.name,
                startedAt = task.createdAt,
                completedAt = task.updatedAt,
                subtasksTotal = subtasks.size,
                subtasksSuccess = subtasks.count { it.status == TaskStatus.SUCCESS },
                subtasksFailed = subtasks.count { it.status == TaskStatus.FAILED },
                tokensUsed = task.tokensIn + task.tokensOut,
                costUsd = task.costUsd
            )
        }
    }

    // ============================================================
    // Helper Methods
    // ============================================================

    /**
     * Map tool name (string) to SubtaskKind enum
     */
    private fun mapToolNameToSubtaskKind(toolName: String): SubtaskKind {
        return when (toolName.lowercase()) {
            "read_file" -> SubtaskKind.READ_FILE
            "read_directory" -> SubtaskKind.READ_DIRECTORY
            "file_search" -> SubtaskKind.FILE_SEARCH
            "grep_search" -> SubtaskKind.GREP_SEARCH
            "view_diff" -> SubtaskKind.VIEW_DIFF
            "code_editing" -> SubtaskKind.CODE_EDITING
            "advance_code_editing" -> SubtaskKind.ADVANCE_CODE_EDITING
            "multi_line_editor" -> SubtaskKind.MULTI_LINE_EDITOR
            "create_new_file" -> SubtaskKind.CREATE_NEW_FILE
            "multi_edit" -> SubtaskKind.MULTI_EDIT
            "run_terminal_command" -> SubtaskKind.RUN_TERMINAL_COMMAND
            "knowledge_base" -> SubtaskKind.KNOWLEDGE_BASE
            "project_analysis" -> SubtaskKind.PROJECT_ANALYSIS
            else -> {
                logger.warn { "Unknown tool name: $toolName, defaulting to PLAN_STEP" }
                SubtaskKind.PLAN_STEP
            }
        }
    }
}
