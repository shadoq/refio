package pl.jclab.refio.core.services

import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.services.execution.unified.ExecutionEventListener
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.implementations.AdvanceCodeEditingTool
import pl.jclab.refio.core.tools.implementations.MultiLineEditorTool
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo

private val logger = dualLogger("ToolExecutor")

/**
 * Tool Executor - executes agent tools sequentially
 *
 * Responsibilities:
 * - Execute tools using ToolRegistry
 * - Validate parameters
 * - Handle errors with proper ToolResult format
 * - Track execution metrics
 * - Stop on first error
 * - Check tool permissions before execution
 * - Create snapshots before write operations
 *
 * Integrates with the tools system (pl.jclab.refio.core.tools).
 */
class ToolExecutor(
    private val toolRegistry: ToolRegistry,
    private val taskRepository: TaskRepository? = null,
    private val subtaskRepository: SubtaskRepository? = null,
    private val snapshotService: SnapshotService? = null,
    private val toolPermissionsService: ToolPermissionsService? = null,
    private val mode: TaskMode = TaskMode.AGENT,
    private val executionMode: ExecutionMode = ExecutionMode.AUTO
) {

    /**
     * Execute list of tool calls sequentially.
     *
     * Stops at first error.
     *
     * @param toolCalls List of tool call specifications
     * @return Execution results with success status, outputs, and errors
     */
    suspend fun executeTools(toolCalls: List<ToolCall>): ToolExecutionResult {
        logger.info { "Executing ${toolCalls.size} tool calls in $mode mode" }

        val outputs = mutableListOf<ToolCallOutput>()
        val errors = mutableListOf<String>()
        var toolsExecuted = 0
        var overallSuccess = true

        val effectiveMode = resolveTaskMode(null)

        for ((index, toolCall) in toolCalls.withIndex()) {
            // Check cancellation
            if (pl.jclab.refio.core.services.monitoring.GlobalMetrics.isCancelled()) {
                logger.info { "Tool execution cancelled by user" }
                throw java.util.concurrent.CancellationException("Operation cancelled by user")
            }

            logger.info { "Executing tool ${index + 1}/${toolCalls.size}: ${toolCall.name}" }

            try {
                // Get tool from registry
                val tool = toolRegistry.getTool(toolCall.name)
                    ?: throw ToolNotFoundException("Tool not found: ${toolCall.name}")

                // Validate tool mode
                if (!isToolAllowedInMode(tool, effectiveMode)) {
                    throw ToolNotAllowedException(
                        "Tool ${toolCall.name} (${tool.mode}) not allowed in $effectiveMode mode"
                    )
                }

                val toolToken = GlobalMetrics.beginOperation(
                    OperationInfo.ExecutingTool(toolName = toolCall.name, stepNumber = 0)
                )
                val result = try {
                    // Execute tool
                    tool.execute(toolCall.params)
                } finally {
                    GlobalMetrics.endOperation(toolToken)
                }

                // Convert to output format
                val output = ToolCallOutput(
                    tool = toolCall.name,
                    params = toolCall.params,
                    result = SingleToolResult(
                        success = result.success,
                        output = result.output,
                        error = result.error,
                        metadata = result.metadata,
                        affectedFiles = result.filesChanged ?: emptyList()
                    )
                )

                outputs.add(output)
                toolsExecuted++

                if (!result.success) {
                    overallSuccess = false
                    val errorMsg = result.error ?: "Unknown error"
                    errors.add("${toolCall.name} failed: $errorMsg")
                    logger.error { "Tool ${toolCall.name} failed: $errorMsg" }
                    break // Stop on first error
                }

            } catch (e: ToolNotFoundException) {
                val errorMsg = "Tool execution error for ${toolCall.name}: ${e.message}"
                logger.error { errorMsg }
                errors.add(errorMsg)
                overallSuccess = false
                break

            } catch (e: ToolNotAllowedException) {
                val errorMsg = "Tool not allowed: ${e.message}"
                logger.error { errorMsg }
                errors.add(errorMsg)
                overallSuccess = false
                break

            } catch (e: Exception) {
                val errorMsg = "Tool execution error for ${toolCall.name}: ${e.message}"
                logger.error(e) { errorMsg }
                errors.add(errorMsg)
                overallSuccess = false
                break
            }
        }

        logger.info { "Tool execution completed: $toolsExecuted/${toolCalls.size} successful" }

        return ToolExecutionResult(
            toolsExecuted = toolsExecuted,
            outputs = outputs,
            success = overallSuccess,
            errors = errors
        )
    }

    /**
     * Execute list of tool calls with streaming support for code generation tools.
     *
     * For advance_code_editing and multi_line_editor tools, streams LLM output to UI.
     * Stops at first error.
     *
     * @param toolCalls List of tool call specifications
     * @param subtask Current subtask (for listener context)
     * @param listener Optional execution event listener for streaming updates
     * @return Execution results with success status, outputs, and errors
     */
    suspend fun executeToolsWithStreaming(
        toolCalls: List<ToolCall>,
        subtask: Subtask,
        listener: ExecutionEventListener?
    ): ToolExecutionResult {
        logger.info { "Executing ${toolCalls.size} tool calls in $mode mode (streaming=${listener != null})" }

        val outputs = mutableListOf<ToolCallOutput>()
        val errors = mutableListOf<String>()
        var toolsExecuted = 0
        var overallSuccess = true

        val effectiveMode = resolveTaskMode(subtask.taskId)

        for ((index, toolCall) in toolCalls.withIndex()) {
            logger.info { "Executing tool ${index + 1}/${toolCalls.size}: ${toolCall.name}" }

            try {
                // Get tool from registry
                val tool = toolRegistry.getTool(toolCall.name)
                    ?: throw ToolNotFoundException("Tool not found: ${toolCall.name}")

                // Validate tool mode
                if (!isToolAllowedInMode(tool, effectiveMode)) {
                    throw ToolNotAllowedException(
                        "Tool ${toolCall.name} (${tool.mode}) not allowed in $effectiveMode mode"
                    )
                }

                // Create snapshot before write operations
                createSnapshotIfNeeded(tool, toolCall, subtask)

                val toolToken = GlobalMetrics.beginOperation(
                    OperationInfo.ExecutingTool(toolName = toolCall.name, stepNumber = subtask.orderIndex)
                )
                val result = try {
                    // Execute tool with streaming for advance_code_editing
                    if (tool is AdvanceCodeEditingTool && listener != null) {
                        executeAdvanceCodeEditingWithStreaming(
                            tool = tool,
                            toolCall = toolCall,
                            subtask = subtask,
                            listener = listener
                        )
                    } else if (tool is MultiLineEditorTool && listener != null) {
                        executeMultiLineEditorWithStreaming(
                            tool = tool,
                            toolCall = toolCall,
                            subtask = subtask,
                            listener = listener
                        )
                    } else {
                        tool.execute(toolCall.params)
                    }
                } finally {
                    GlobalMetrics.endOperation(toolToken)
                }

                // Convert to output format
                val output = ToolCallOutput(
                    tool = toolCall.name,
                    params = toolCall.params,
                    result = SingleToolResult(
                        success = result.success,
                        output = result.output,
                        error = result.error,
                        metadata = result.metadata,
                        affectedFiles = result.filesChanged ?: emptyList()
                    )
                )

                outputs.add(output)
                toolsExecuted++

                if (!result.success) {
                    overallSuccess = false
                    val errorMsg = result.error ?: "Unknown error"
                    errors.add("${toolCall.name} failed: $errorMsg")
                    logger.error { "Tool ${toolCall.name} failed: $errorMsg" }
                    break // Stop on first error
                }

            } catch (e: ToolNotFoundException) {
                val errorMsg = "Tool execution error for ${toolCall.name}: ${e.message}"
                logger.error { errorMsg }
                errors.add(errorMsg)
                overallSuccess = false
                break

            } catch (e: ToolNotAllowedException) {
                val errorMsg = "Tool not allowed: ${e.message}"
                logger.error { errorMsg }
                errors.add(errorMsg)
                overallSuccess = false
                break

            } catch (e: Exception) {
                val errorMsg = "Tool execution error for ${toolCall.name}: ${e.message}"
                logger.error(e) { errorMsg }
                errors.add(errorMsg)
                overallSuccess = false
                break
            }
        }

        logger.info { "Tool execution completed: $toolsExecuted/${toolCalls.size} successful" }

        return ToolExecutionResult(
            toolsExecuted = toolsExecuted,
            outputs = outputs,
            success = overallSuccess,
            errors = errors
        )
    }

    /**
     * Execute AdvanceCodeEditingTool with streaming to UI.
     *
     * Uses the tool's executeWithListener method which directly integrates
     * with ExecutionEventListener for streaming code generation to ChatView.
     */
    private suspend fun executeAdvanceCodeEditingWithStreaming(
        tool: AdvanceCodeEditingTool,
        toolCall: ToolCall,
        subtask: Subtask,
        listener: ExecutionEventListener
    ): ToolResult {
        val filePath = toolCall.params["path"] as? String ?: "unknown"
        logger.info { "[STREAM] Starting code generation stream for: $filePath" }

        // Use tool's direct listener integration
        return tool.executeWithListener(
            params = toolCall.params,
            subtask = subtask,
            listener = listener
        )
    }

    /**
     * Execute MultiLineEditorTool with streaming to UI.
     */
    private suspend fun executeMultiLineEditorWithStreaming(
        tool: MultiLineEditorTool,
        toolCall: ToolCall,
        subtask: Subtask,
        listener: ExecutionEventListener
    ): ToolResult {
        val filePath = toolCall.params["path"] as? String ?: "unknown"
        logger.info { "[STREAM] Starting multi-line edit stream for: $filePath" }

        return tool.executeWithListener(
            params = toolCall.params,
            subtask = subtask,
            listener = listener
        )
    }

    /**
     * Execute a single tool call.
     *
     * @param toolCall Tool call specification
     * @param taskId Optional task ID for permission checking
     * @return Tool execution result
     */
    suspend fun executeTool(toolCall: ToolCall, taskId: String? = null): ToolResult {
        logger.info { "Executing single tool: ${toolCall.name}" }

        // Get tool from registry
        val tool = toolRegistry.getTool(toolCall.name)
            ?: throw ToolNotFoundException("Tool not found: ${toolCall.name}")

        // Validate tool mode
        val effectiveMode = resolveTaskMode(taskId)
        if (!isToolAllowedInMode(tool, effectiveMode)) {
            throw ToolNotAllowedException(
                "Tool ${toolCall.name} (${tool.mode}) not allowed in $effectiveMode mode"
            )
        }

        // Check tool permissions (if service is available)
        if (toolPermissionsService != null && taskId != null) {
            checkToolPermissions(toolCall.name, taskId)
        }

        // Execute tool
        return tool.execute(toolCall.params)
    }

    /**
     * Check tool permissions before execution.
     * Throws exception if tool is not allowed.
     *
     * @param toolName Tool name
     * @param taskId Task ID
     */
    private fun checkToolPermissions(toolName: String, taskId: String) {
        if (toolPermissionsService == null || taskRepository == null) {
            return
        }

        val task = taskRepository.findById(taskId)
            ?: throw IllegalArgumentException("Task not found: $taskId")

        val permission = toolPermissionsService.getPermission(
            toolName = toolName,
            taskMode = task.mode,
            taskId = taskId
        )

        when (permission) {
            PermissionLevel.OFF -> {
                throw SecurityException("Tool $toolName is disabled for ${task.mode} mode")
            }
            PermissionLevel.ON, PermissionLevel.ASK -> {
                // ASK is handled at TurnToolExecutor level (approval flow).
                // At ToolExecutor level, ASK tools are allowed to execute.
                logger.debug { "Tool $toolName allowed (permission=$permission)" }
            }
        }
    }

    /**
     * List available tools for current mode.
     *
     * @param readOnly If true, return only read-only tools
     * @return List of tool names
     */
    fun listAvailableTools(readOnly: Boolean = false): List<String> {
        val effectiveMode = if (readOnly) ToolMode.READ_ONLY else getToolMode()

        return toolRegistry.getAllTools()
            .filter { tool ->
                when (effectiveMode) {
                    ToolMode.READ_ONLY -> tool.mode == ToolMode.READ_ONLY
                    ToolMode.WRITE -> true // WRITE mode can use both READ_ONLY and WRITE tools
                }
            }
            .map { it.name }
    }

    /**
     * Get tool descriptions for LLM consumption.
     *
     * @return List of tool descriptions with schemas
     */
    fun getToolsForLLM(): List<ToolDescription> {
        val effectiveMode = getToolMode()

        return toolRegistry.getAllTools()
            .filter { tool ->
                when (effectiveMode) {
                    ToolMode.READ_ONLY -> tool.mode == ToolMode.READ_ONLY
                    ToolMode.WRITE -> true
                }
            }
            .map { tool ->
                ToolDescription(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.getParameterSchema()
                )
            }
    }

    /**
     * Check if tool is allowed in current mode.
     */
    private fun isToolAllowedInMode(tool: Tool, taskMode: TaskMode): Boolean {
        return when (taskMode) {
            TaskMode.CHAT -> tool.mode == ToolMode.READ_ONLY // CHAT can only use read-only tools
            TaskMode.PLAN -> tool.mode == ToolMode.READ_ONLY // PLAN can only use read-only tools
            TaskMode.AGENT -> true // AGENT can use both READ_ONLY and WRITE tools
        }
    }

    /**
     * Convert TaskMode to ToolMode.
     */
    private fun getToolMode(): ToolMode {
        return when (mode) {
            TaskMode.CHAT, TaskMode.PLAN -> ToolMode.READ_ONLY
            TaskMode.AGENT -> ToolMode.WRITE
        }
    }

    private fun resolveTaskMode(taskId: String?): TaskMode {
        if (taskId == null || taskRepository == null) {
            return mode
        }

        val task = taskRepository.findById(taskId)
            ?: throw IllegalArgumentException("Task not found: $taskId")

        return task.mode
    }

    /**
     * Extract file paths from tool parameters.
     * Looks for common parameter names: path, file_path, paths, files.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun extractFilePaths(_toolName: String, params: Map<String, Any>): List<String> {
        val paths = mutableListOf<String>()

        // Single file path
        (params["path"] as? String)?.let { paths.add(it) }
        (params["file_path"] as? String)?.let { paths.add(it) }

        // Multiple file paths
        @Suppress("UNCHECKED_CAST")
        (params["paths"] as? List<String>)?.let { paths.addAll(it) }
        @Suppress("UNCHECKED_CAST")
        (params["files"] as? List<String>)?.let { paths.addAll(it) }

        return paths.distinct()
    }

    /**
     * Create snapshot before write operation (if services available).
     * Links snapshot to subtask for rollback capability.
     *
     * @param tool Tool being executed
     * @param toolCall Tool call with parameters
     * @param subtask Current subtask
     */
    private fun createSnapshotIfNeeded(tool: Tool, toolCall: ToolCall, subtask: Subtask) {
        // Skip if services not available or not write tool
        if (snapshotService == null || subtaskRepository == null) {
            return
        }

        if (tool.mode != ToolMode.WRITE) {
            return
        }

        try {
            // Extract file paths from parameters
            val filePaths = extractFilePaths(toolCall.name, toolCall.params)

            if (filePaths.isEmpty()) {
                logger.debug { "No file paths found in ${toolCall.name} params, skipping snapshot" }
                return
            }

            logger.info { "Creating snapshot before ${toolCall.name}: ${filePaths.size} file(s)" }

            // Create snapshot (returns subtaskId as snapshotId)
            val snapshotId = snapshotService.createSnapshot(
                taskId = subtask.taskId,
                subtaskId = subtask.id,
                filePaths = filePaths
            )

            // Link snapshot to subtask
            subtaskRepository.linkSnapshot(subtask.id, snapshotId)

            logger.info { "Snapshot created and linked: $snapshotId for ${filePaths.joinToString()}" }

        } catch (e: Exception) {
            logger.warn(e) { "Failed to create snapshot for ${toolCall.name}, continuing without snapshot" }
            // Don't fail execution if snapshot fails - continue anyway
        }
    }
}

/**
 * Tool call specification
 */
data class ToolCall(
    /**
     * Tool name
     */
    val name: String,

    /**
     * Tool parameters
     */
    val params: Map<String, Any>,

    /**
     * Expected output description (optional, for planning)
     */
    val expectedOutput: String? = null
)

/**
 * Tool execution result - batch result returned by executeTools()
 */
data class ToolExecutionResult(
    /**
     * Number of tools successfully executed
     */
    val toolsExecuted: Int,

    /**
     * List of tool outputs
     */
    val outputs: List<ToolCallOutput>,

    /**
     * Overall success status
     */
    val success: Boolean,

    /**
     * List of errors (if any)
     */
    val errors: List<String>
)

/**
 * Single tool call output
 */
data class ToolCallOutput(
    /**
     * Tool name
     */
    val tool: String,

    /**
     * Parameters used
     */
    val params: Map<String, Any>,

    /**
     * Execution result
     */
    val result: SingleToolResult
)

/**
 * Single tool execution result (simplified from ToolResult)
 */
data class SingleToolResult(
    /**
     * Success status
     */
    val success: Boolean,

    /**
     * Output content
     */
    val output: String?,

    /**
     * Error message (if failed)
     */
    val error: String?,

    /**
     * Additional metadata
     */
    val metadata: Map<String, Any>?,

    /**
     * Files affected by this operation
     */
    val affectedFiles: List<String>
)

/**
 * Tool description for LLM
 */
data class ToolDescription(
    /**
     * Tool name
     */
    val name: String,

    /**
     * Human-readable description
     */
    val description: String,

    /**
     * Parameter schema (JSON Schema)
     */
    val parameters: Map<String, Any>
)

/**
 * Exception thrown when tool is not found
 */
class ToolNotFoundException(message: String) : Exception(message)

/**
 * Exception thrown when tool is not allowed in current mode
 */
class ToolNotAllowedException(message: String) : Exception(message)
