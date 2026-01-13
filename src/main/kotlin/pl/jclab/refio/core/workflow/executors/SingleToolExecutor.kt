package pl.jclab.refio.core.workflow.executors

import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.WorkflowIntent
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("SingleToolExecutor")

/**
 * Executor for single tool execution.
 *
 * Used when intent classification determines that a task can be completed
 * with a single tool call (e.g., reading a file, searching for text).
 *
 * See docs/features/0017-new-workflow.md for specification.
 */
class SingleToolExecutor(
    private val toolRegistry: ToolRegistry
) {
    /**
     * Execute a single tool based on the ExecuteTool intent.
     *
     * @param intent The ExecuteTool intent with tool name and arguments
     * @return ToolResult with execution output
     */
    suspend fun execute(intent: WorkflowIntent.ExecuteTool): IntentResult.ToolResult {
        val toolName = intent.toolName
        val toolArgs = intent.toolArgs

        logger.info { "[TOOL_EXECUTOR] Executing tool: $toolName with args: $toolArgs" }

        val tool = toolRegistry.getTool(toolName)
        if (tool == null) {
            logger.error { "[TOOL_EXECUTOR] Tool not found: $toolName" }
            return IntentResult.ToolResult(
                taskId = intent.taskId,
                toolName = toolName,
                output = "Error: Tool '$toolName' not found in registry",
                success = false
            )
        }

        return try {
            // Validate parameters
            tool.validateParams(toolArgs)

            // Execute the tool
            val result = tool.execute(toolArgs)

            logger.info {
                "[TOOL_EXECUTOR] Tool execution complete: $toolName, success=${result.success}"
            }

            if (result.success) {
                IntentResult.ToolResult(
                    taskId = intent.taskId,
                    toolName = toolName,
                    output = result.output ?: "Tool executed successfully (no output)",
                    success = true
                )
            } else {
                IntentResult.ToolResult(
                    taskId = intent.taskId,
                    toolName = toolName,
                    output = result.error ?: "Tool execution failed (unknown error)",
                    success = false
                )
            }
        } catch (e: IllegalArgumentException) {
            logger.warn { "[TOOL_EXECUTOR] Invalid parameters for $toolName: ${e.message}" }
            IntentResult.ToolResult(
                taskId = intent.taskId,
                toolName = toolName,
                output = "Parameter validation error: ${e.message}",
                success = false
            )
        } catch (e: Exception) {
            logger.error(e) { "[TOOL_EXECUTOR] Tool execution failed: $toolName" }
            IntentResult.ToolResult(
                taskId = intent.taskId,
                toolName = toolName,
                output = "Execution error: ${e.message}",
                success = false
            )
        }
    }
}
