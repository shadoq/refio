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
 * Groups tools by logical sections (Reading & Search, Web, Editing, etc.)
 * with section headers for better LLM comprehension.
 */
class ToolDescriptionBuilder(
    private val toolRegistry: ToolRegistry,
    private val toolPermissionsService: ToolPermissionsService? = null,
    var compactMode: Boolean = false
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
     * Groups tools by logical sections (Reading, Web, Editing, etc.) with headers.
     */
    fun getToolDescriptionsForTools(mode: TaskMode, tools: List<Tool>): String {
        val modeNote = when (mode) {
            TaskMode.CHAT, TaskMode.PLAN -> "READ-ONLY TOOLS (you can use these in ${mode.name} mode):"
            TaskMode.AGENT -> "AVAILABLE TOOLS (read-only and write operations):"
        }

        val groups = toolRegistry.getToolsByGroups(tools)
        var number = 1
        val sb = StringBuilder()
        sb.appendLine(modeNote)

        for ((groupName, groupTools) in groups) {
            sb.appendLine()
            sb.appendLine("### $groupName\n")
            for (tool in groupTools) {
                val schema = tool.getParameterSchema()
                sb.append(buildToolDescription(number, tool.name, tool.description, schema))
                number++
            }
        }

        return sb.toString().trimEnd()
    }

    /**
     * Get valid tool names for a pre-filtered tool list.
     */
    fun getValidToolNamesForTools(tools: List<Tool>): String {
        return tools.joinToString(", ") { it.name }
    }

    /**
     * Build the When-to-use-what selection matrix for the given mode.
     * Only tools that provide a [Tool.selectionHint] appear. Grouped by the same
     * logical sections used by tool descriptions — if a permission filter hides a
     * tool, it disappears from the matrix too.
     */
    fun getToolSelectionMatrix(mode: TaskMode, taskId: String? = null): String {
        return buildSelectionMatrix(getToolsForMode(mode, taskId))
    }

    /**
     * Build the selection matrix from a pre-filtered tool list. Returns empty
     * string if no tool in the list provides a selection hint.
     */
    fun buildSelectionMatrix(tools: List<Tool>): String {
        val groups = toolRegistry.getToolsByGroups(tools)
        val sb = StringBuilder()
        var anyRow = false

        sb.appendLine("| Tool | When to use |")
        sb.appendLine("|---|---|")
        for ((_, groupTools) in groups) {
            for (tool in groupTools) {
                val hint = tool.selectionHint?.trim().orEmpty()
                if (hint.isBlank()) continue
                val safeHint = hint.replace("|", "\\|").replace("\n", " ")
                sb.append("| `").append(tool.name).append("` | ").append(safeHint).append(" |\n")
                anyRow = true
            }
        }

        return if (anyRow) sb.toString().trimEnd() else ""
    }

    /**
     * Get tools filtered by task mode AND permissions.
     */
    fun getToolsForMode(mode: TaskMode, taskId: String? = null) =
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
     * In compact mode: only required params listed, no examples, shorter descriptions.
     */
    private fun buildToolDescription(
        number: Int,
        name: String,
        description: String,
        schema: Map<String, Any>
    ): String {
        if (compactMode) {
            return buildCompactToolDescription(number, name, description, schema)
        }

        val sb = StringBuilder()
        sb.append("$number. **$name** - $description\n")

        @Suppress("UNCHECKED_CAST")
        val properties = (schema["properties"] as? Map<String, Map<String, Any>>) ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val required = (schema["required"] as? List<String>) ?: emptyList()

        if (properties.isNotEmpty()) {
            val requiredParams = properties.filter { it.key in required }
            val optionalParams = properties.filter { it.key !in required }

            requiredParams.forEach { (paramName, paramSchema) ->
                val paramType = paramSchema["type"]?.toString() ?: "any"
                val paramDesc = paramSchema["description"]?.toString() ?: ""
                sb.append("   - **\"$paramName\"** ($paramType)")
                if (paramDesc.isNotBlank()) sb.append(" — $paramDesc")
                sb.append("\n")
            }

            optionalParams.forEach { (paramName, paramSchema) ->
                val paramType = paramSchema["type"]?.toString() ?: "any"
                val paramDesc = paramSchema["description"]?.toString() ?: ""
                sb.append("   - \"$paramName\" ($paramType, optional)")
                if (paramDesc.isNotBlank()) sb.append(" — $paramDesc")
                sb.append("\n")
            }
        }

        // Add example
        val exampleParams = properties.keys.take(2).associateWith {
            when (it) {
                "path" -> "src/main.kt"
                "command" -> "gradle test"
                "pattern" -> "*.kt"
                "content" -> "// file content"
                "old_string" -> "oldValue"
                "new_string" -> "newValue"
                else -> "value"
            }
        }

        if (exampleParams.isNotEmpty()) {
            sb.append("\nExample: {\"tool\": \"$name\", \"args\": ${gson.toJson(exampleParams)}}\n\n")
        }

        return sb.toString()
    }

    /**
     * Compact tool description: required params only, truncated descriptions, no examples.
     * Saves ~40-50% tokens per tool compared to full format.
     */
    private fun buildCompactToolDescription(
        number: Int,
        name: String,
        description: String,
        schema: Map<String, Any>
    ): String {
        val sb = StringBuilder()
        // Truncate description to first sentence
        val shortDesc = description.substringBefore(". ").substringBefore(".\n").take(120)
        sb.append("$number. **$name** — $shortDesc\n")

        @Suppress("UNCHECKED_CAST")
        val properties = (schema["properties"] as? Map<String, Map<String, Any>>) ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val required = (schema["required"] as? List<String>) ?: emptyList()

        if (properties.isNotEmpty()) {
            // Required params with short description
            val requiredParams = properties.filter { it.key in required }
            val optionalParams = properties.filter { it.key !in required }

            requiredParams.forEach { (paramName, paramSchema) ->
                val paramType = paramSchema["type"]?.toString() ?: "any"
                val paramDesc = (paramSchema["description"]?.toString() ?: "")
                    .substringBefore(". ").take(80)
                sb.append("   - \"$paramName\" ($paramType) — $paramDesc\n")
            }

            // Optional params as a compact list
            if (optionalParams.isNotEmpty()) {
                val optNames = optionalParams.keys.joinToString(", ") { "\"$it\"" }
                sb.append("   - Optional: $optNames\n")
            }
        }

        return sb.toString()
    }
}
