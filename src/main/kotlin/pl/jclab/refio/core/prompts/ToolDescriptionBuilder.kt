package pl.jclab.refio.core.prompts

import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.utils.GsonInstance.prettyGson

/**
 * Builder for generating tool descriptions for LLM prompts.
 *
 * Provides different tool sets based on task mode:
 * - CHAT/PLAN: Read-only tools (read_file, grep_search, etc.) filtered by permissions
 * - AGENT: All tools including write operations filtered by permissions
 *
 * Uses ToolRegistry and ToolPermissionsService to dynamically generate descriptions
 * with parameter schemas.
 */
class ToolDescriptionBuilder(
    private val toolRegistry: ToolRegistry,
    private val toolPermissionsService: ToolPermissionsService? = null
) {
    private val gson = prettyGson

    /**
     * Get tool descriptions based on task mode.
     * Dynamically generates descriptions from ToolRegistry with full parameter schemas.
     *
     * @param mode Task mode (CHAT, PLAN, AGENT)
     * @param taskId Optional task ID for task-level permissions
     * @return Tool descriptions string with parameter schemas
     */
    fun getToolDescriptions(mode: TaskMode, taskId: String? = null): String {
        return getToolDescriptionsForTools(mode, getToolsForMode(mode, taskId))
    }

    /**
     * Get tool description for a specific tool only.
     * Used when we want to constrain the LLM to use only the suggested tool.
     *
     * @param mode Task mode
     * @param toolName Name of the tool to describe
     * @param taskId Optional task ID for task-level permissions
     * @return Tool description string with parameter schema, or error message if tool not found/available
     */
    fun getSingleToolDescription(mode: TaskMode, toolName: String, taskId: String? = null): String {
        val allTools = getToolsForMode(mode, taskId)
        val tool = allTools.find { it.name == toolName }

        if (tool == null) {
            return "ERROR: Tool '$toolName' not found or not available in ${mode.name} mode."
        }

        val schema = tool.getParameterSchema()
        val description = buildToolDescription(1, tool.name, tool.description, schema)

        val modeNote = when (mode) {
            TaskMode.CHAT, TaskMode.PLAN -> "SUGGESTED TOOL (you MUST use this tool in ${mode.name} mode):"
            TaskMode.AGENT -> "SUGGESTED TOOL (you should use this tool):"
        }

        return "$modeNote\n\n$description"
    }

    /**
     * Get list of valid tool names for validation.
     *
     * @param mode Task mode
     * @param taskId Optional task ID for task-level permissions
     * @return Comma-separated list of tool names
     */
    fun getValidToolNames(mode: TaskMode, taskId: String? = null): String {
        return getValidToolNamesForTools(getToolsForMode(mode, taskId))
    }

    /**
     * Get tool descriptions for a pre-filtered tool list.
     * Keeps prompts consistent with task-specific constraints (e.g., read-only).
     */
    fun getToolDescriptionsForTools(mode: TaskMode, tools: List<Tool>): String {
        val descriptions = tools.mapIndexed { index, tool ->
            val schema = tool.getParameterSchema()
            buildToolDescription(index + 1, tool.name, tool.description, schema)
        }.joinToString("\n\n")

        val modeNote = when (mode) {
            TaskMode.CHAT, TaskMode.PLAN -> "READ-ONLY TOOLS (you can use these in ${mode.name} mode):"
            TaskMode.AGENT -> "AVAILABLE TOOLS (read-only and write operations):"
        }

        return "$modeNote\n\n$descriptions"
    }

    /**
     * Get valid tool names for a pre-filtered tool list.
     */
    fun getValidToolNamesForTools(tools: List<Tool>): String {
        return tools.joinToString(", ") { it.name }
    }

    /**
     * Get tools filtered by task mode AND permissions.
     */
    private fun getToolsForMode(mode: TaskMode, taskId: String? = null) =
        if (toolPermissionsService != null) {
            // Use permission-based filtering
            toolRegistry.getAvailableTools(mode, toolPermissionsService, taskId)
        } else {
            // Fallback to old behavior (no permission filtering)
            when (mode) {
                TaskMode.CHAT, TaskMode.PLAN -> toolRegistry.getReadOnlyTools()
                TaskMode.AGENT -> toolRegistry.getAllTools()
            }
        }

    /**
     * Build human-readable tool description with parameter schema.
     */
    private fun buildToolDescription(
        number: Int,
        name: String,
        description: String,
        schema: Map<String, Any>
    ): String {
        val sb = StringBuilder()
        sb.append("$number. **$name** - $description\n")

        @Suppress("UNCHECKED_CAST")
        val properties = (schema["properties"] as? Map<String, Map<String, Any>>) ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val required = (schema["required"] as? List<String>) ?: emptyList()

        if (properties.isNotEmpty()) {
            properties.forEach { (paramName, paramSchema) ->
                val paramType = paramSchema["type"]?.toString() ?: "any"
                val paramDesc = paramSchema["description"]?.toString() ?: ""
                val isRequired = paramName in required
                val requiredLabel = if (isRequired) "Required" else "Optional"

                sb.append("   - $requiredLabel: \"$paramName\" ($paramType)")
                if (paramDesc.isNotBlank()) {
                    sb.append(" - $paramDesc")
                }
                sb.append("\n")
            }
        }

        // Add example
        val exampleParams = properties.keys.take(2).associateWith {
            when (it) {
                "path" -> "src/Main.kt"
                "command" -> "gradle test"
                "pattern" -> "*.kt"
                "content" -> "// file content"
                "old_string" -> "oldValue"
                "new_string" -> "newValue"
                else -> "value"
            }
        }

        if (exampleParams.isNotEmpty()) {
            sb.append("   - Example: {\"tool\": \"$name\", \"args\": ${gson.toJson(exampleParams)}}\n")
        }

        return sb.toString()
    }
}
