package pl.jclab.refio.core.services.orchestration

import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.Task
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.services.PermissionLevel
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("PlanModifier")

/**
 * Plan Modifier - dynamically modifies execution plan.
 *
 * Allows orchestrator to:
 * - Add new subtasks
 * - Skip subtasks
 * - Modify subtask parameters
 * - Reorder subtasks
 */
class PlanModifier(
    private val subtaskRepository: SubtaskRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val toolRegistry: ToolRegistry,
    private val toolPermissionsService: ToolPermissionsService?,
    private val taskRepository: TaskRepository
) {

    /**
     * Add new subtask after specified step.
     *
     * Inserts new subtask with order_index between afterStep and next step.
     */
    suspend fun addSubtask(
        taskId: String,
        afterStep: Int,
        description: String,
        kind: String,
        suggestedParams: Map<String, Any>,
        requiresApproval: Boolean = false
    ): Subtask {
        logger.info { "[PLAN_MODIFIER] Adding subtask after step $afterStep: $description" }

        val task = taskRepository.findById(taskId)
            ?: throw IllegalArgumentException("Task not found: $taskId")

        ensureToolAvailable(kind, task)

        // Find existing subtasks to calculate insertion point
        val subtasks = subtaskRepository.findByTaskId(taskId).sortedBy { it.orderIndex }

        // Calculate new order_index - afterStep is the actual orderIndex value
        val afterSubtask = subtasks.find { it.orderIndex == afterStep }

        val newOrderIndex = if (afterSubtask != null) {
            // Insert after the specified step
            afterSubtask.orderIndex + 1
        } else if (subtasks.isNotEmpty()) {
            // afterStep not found, insert at end (append after last step)
            subtasks.maxOf { it.orderIndex } + 1
        } else {
            // No subtasks exist, start at 1
            1
        }

        // Map kind string to SubtaskKind enum
        val subtaskKind = mapKindToEnum(kind)

        // Create params_json with intent
        val paramsJson = gson.toJson(mapOf(
            "intent" to description,
            "tool_type" to kind,
            "suggested_params" to suggestedParams,
            "added_by" to "orchestrator"
        ))

        // Use atomic createWithShift to avoid UNIQUE constraint violations
        // This method shifts all subtasks at newOrderIndex and above by +1, then inserts the new one
        val subtask = subtaskRepository.createWithShift(
            taskId = taskId,
            insertAt = newOrderIndex,
            kind = subtaskKind,
            description = description,
            paramsJson = paramsJson,
            requiresApproval = requiresApproval
        )

        logger.info { "[PLAN_MODIFIER] Added subtask: ${subtask.id} at order_index=$newOrderIndex" }

        // Save change notification to chat
        saveChangeToChat(taskId, "🆕 Added step $newOrderIndex: $description")

        return subtask
    }

    /**
     * Skip subtask - mark as CANCELED status.
     *
     * @param step The orderIndex of the step to skip
     */
    suspend fun skipSubtask(
        taskId: String,
        step: Int,
        reason: String
    ) {
        logger.info { "[PLAN_MODIFIER] Skipping step $step: $reason" }

        val subtasks = subtaskRepository.findByTaskId(taskId)

        // Find step by direct orderIndex match
        val subtask = subtasks.find { it.orderIndex == step }
            ?: run {
                val available = subtasks.filter { it.status == TaskStatus.PENDING }
                    .sortedBy { it.orderIndex }
                    .map { "step ${it.orderIndex}" }
                    .joinToString(", ")
                throw IllegalArgumentException("Step $step not found. Available pending steps: $available")
            }

        // Update status to CANCELED (we use CANCELED as SKIPPED)
        subtaskRepository.updateStatus(subtask.id, TaskStatus.CANCELED)
        subtaskRepository.updateResult(
            id = subtask.id,
            result = null,
            errorMessage = "Skipped by orchestrator: $reason"
        )

        // Save to chat
        saveChangeToChat(taskId, "⏭️ Skipped step $step: $reason")
    }

    /**
     * Modify subtask parameters or description.
     *
     * @param step The orderIndex of the step to modify
     */
    suspend fun modifySubtask(
        taskId: String,
        step: Int,
        newDescription: String?,
        newParams: Map<String, Any>?
    ) {
        logger.info { "[PLAN_MODIFIER] Modifying step $step" }

        val subtasks = subtaskRepository.findByTaskId(taskId)
        val subtask = subtasks.find { it.orderIndex == step }
            ?: run {
                val available = subtasks.sortedBy { it.orderIndex }
                    .map { "step ${it.orderIndex}" }
                    .joinToString(", ")
                throw IllegalArgumentException("Step $step not found. Available steps: $available")
            }

        // Parse existing params
        val existingParams = if (subtask.paramsJson != null) {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(subtask.paramsJson, Map::class.java) as Map<String, Any>
        } else {
            emptyMap()
        }

        // Merge with new params
        val updatedParams = if (newParams != null) {
            existingParams + newParams + mapOf("modified_by" to "orchestrator")
        } else {
            existingParams
        }

        val updatedParamsJson = gson.toJson(updatedParams)

        // Update subtask
        subtaskRepository.update(
            id = subtask.id,
            description = newDescription ?: subtask.description,
            paramsJson = updatedParamsJson
        )

        // Save to chat
        val changes = mutableListOf<String>()
        if (newDescription != null) changes.add("description")
        if (newParams != null) changes.add("parameters")

        saveChangeToChat(taskId, "✏️ Modified step $step: ${changes.joinToString(", ")}")
    }

    /**
     * Retry failed step by resetting its status to PENDING.
     *
     * @param step The orderIndex of the step to retry
     */
    suspend fun retrySubtask(
        taskId: String,
        step: Int,
        reason: String
    ) {
        logger.info { "[PLAN_MODIFIER] Retrying step $step: $reason" }

        val subtasks = subtaskRepository.findByTaskId(taskId)
        val subtask = subtasks.find { it.orderIndex == step }
            ?: run {
                val available = subtasks.sortedBy { it.orderIndex }
                    .map { "step ${it.orderIndex}" }
                    .joinToString(", ")
                throw IllegalArgumentException("Step $step not found. Available steps: $available")
            }

        // Reset status to PENDING
        subtaskRepository.updateStatus(subtask.id, TaskStatus.PENDING)

        // Clear previous results
        subtaskRepository.updateResult(
            id = subtask.id,
            result = null,
            errorMessage = null
        )

        // Save to chat
        saveChangeToChat(taskId, "🔄 Retrying step $step: $reason")
    }

    /**
     * Save plan change notification to chat messages.
     */
    private suspend fun saveChangeToChat(taskId: String, change: String) {
        chatMessageRepository.create(
            taskId = taskId,
            role = MessageRole.SYSTEM,
            content = "**Plan Updated:** $change",
            metadata = gson.toJson(mapOf(
                "type" to "plan_modification",
                "timestamp" to System.currentTimeMillis()
            ))
        )
    }

    private fun ensureToolAvailable(kind: String, task: Task) {
        val normalized = kind.trim().lowercase()

        if (normalized.isEmpty() || normalized == "plan_step") {
            return
        }

        val tool = toolRegistry.getTool(normalized)
        if (tool == null) {
            // Get available tool names for helpful error message
            val availableTools = toolRegistry.getAllTools()
                .filter { isToolAllowedForMode(it, task.mode) }
                .map { it.name }
                .joinToString(", ")

            throw IllegalArgumentException(
                "Tool '$kind' is not registered in the current workspace.\n\n" +
                "Available tools for ${task.mode} mode: $availableTools\n\n" +
                "Hint: Common mistakes include:\n" +
                "  - 'search' → use 'grep_search' or 'file_search'\n" +
                "  - 'find' → use 'file_search'\n" +
                "  - 'write_file' → use 'create_new_file' or 'code_editing'"
            )
        }

        if (!isToolAllowedForMode(tool, task.mode)) {
            throw IllegalArgumentException("Tool '$kind' (${tool.mode}) not allowed in ${task.mode} mode.")
        }

        if (toolPermissionsService != null) {
            val permission = toolPermissionsService.getPermission(normalized, task.mode, task.id)
            if (permission == PermissionLevel.OFF) {
                throw IllegalArgumentException("Tool '$kind' is disabled for task ${task.id}.")
            }
        }
    }

    private fun isToolAllowedForMode(tool: Tool, mode: TaskMode): Boolean {
        return when (mode) {
            TaskMode.CHAT, TaskMode.PLAN -> tool.mode == ToolMode.READ_ONLY
            TaskMode.AGENT -> true
        }
    }

    private fun mapKindToEnum(kind: String): SubtaskKind {
        return when (kind.lowercase()) {
            "read_file" -> SubtaskKind.READ_FILE
            "code_editing" -> SubtaskKind.CODE_EDITING
            "advance_code_editing" -> SubtaskKind.ADVANCE_CODE_EDITING
            "create_new_file" -> SubtaskKind.CREATE_NEW_FILE
            "multi_edit" -> SubtaskKind.MULTI_EDIT
            "read_directory" -> SubtaskKind.READ_DIRECTORY
            "grep_search" -> SubtaskKind.GREP_SEARCH
            "file_search" -> SubtaskKind.FILE_SEARCH
            "run_terminal_command" -> SubtaskKind.RUN_TERMINAL_COMMAND
            "view_diff" -> SubtaskKind.VIEW_DIFF
            else -> SubtaskKind.PLAN_STEP
        }
    }
}
