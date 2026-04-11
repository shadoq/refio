package pl.jclab.refio.core.tools.base

import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.logging.dualLogger
import java.util.concurrent.ConcurrentHashMap

private val logger = dualLogger("ToolRegistry")

/**
 * Registry of available tools.
 *
 * Maintains a catalog of all tools that can be invoked by the agent.
 * Provides lookup by name and filtering by mode.
 *
 * Thread-safe: Uses ConcurrentHashMap to prevent race conditions during
 * concurrent tool registration from multiple projects.
 */
class ToolRegistry {
    private val tools = ConcurrentHashMap<String, Tool>()

    /**
     * Canonical tool ordering: READ → WRITE (cheapest first) → EXECUTE → DELEGATE.
     * Tools not listed here appear at the end (e.g. MCP tools).
     */
    private val toolOrder = listOf(
        // READ — orientation & analysis
        "read_file", "read_directory", "file_search", "grep_search", "view_diff",
        // SYSTEM — planning, memory, reasoning & agent management
        "think", "tasks", "memory", "manage_subagent",
        // WRITE — cheapest first
        "code_editing", "multi_edit", "create_new_file", "multi_line_editor", "advance_code_editing",
        // EXECUTE — verification & data
        "run_terminal_command", "run_code", "http_request",
        // COMMUNICATE
        "send_message",
        // DELEGATE — only when the agent cannot handle the task itself
        "delegate_to_strong_model", "invoke_subagent"
    )

    /**
     * Register a tool
     *
     * @param tool Tool to register
     * @throws IllegalArgumentException if tool with same name already registered
     */
    fun register(tool: Tool) {
        if (tools.containsKey(tool.name)) {
            throw IllegalArgumentException("Tool already registered: ${tool.name}")
        }

        tools[tool.name] = tool
        logger.info { "Registered tool: ${tool.name} (mode=${tool.mode})" }
    }

    /**
     * Get tool by name
     *
     * @param name Tool name
     * @return Tool instance or null if not found
     */
    fun getTool(name: String): Tool? {
        return tools[name]
    }

    /**
     * Get all registered tools in canonical order (READ → WRITE → EXECUTE → DELEGATE).
     * Tools not in [toolOrder] appear at the end.
     *
     * @return List of all tools
     */
    fun getAllTools(): List<Tool> {
        return sortByCanonicalOrder(tools.values)
    }

    private fun sortByCanonicalOrder(values: Collection<Tool>): List<Tool> {
        val byName = values.associateBy { it.name }
        val ordered = toolOrder.mapNotNull { byName[it] }
        val remaining = values.filter { it.name !in toolOrder }
        return ordered + remaining
    }

    /**
     * Get tools by mode
     *
     * @param mode Tool mode filter
     * @return List of tools matching the mode
     */
    fun getToolsByMode(mode: ToolMode): List<Tool> {
        return sortByCanonicalOrder(tools.values.filter { it.mode == mode })
    }

    /**
     * Get read-only tools (convenience method)
     *
     * @return List of read-only tools
     */
    fun getReadOnlyTools(): List<Tool> {
        return getToolsByMode(ToolMode.READ_ONLY)
    }

    /**
     * Get tools filtered by mode and permissions.
     *
     * @param taskMode Task mode (CHAT/PLAN/AGENT)
     * @param permissionsService Service providing permission checks
     * @param taskId Optional task ID for task-level permissions
     * @return List of available tools
     */
    fun getAvailableTools(
        taskMode: TaskMode,
        permissionsService: ToolPermissionsService,
        taskId: String? = null
    ): List<Tool> {
        return permissionsService.filterAvailableTools(getAllTools(), taskMode, taskId)
    }

    /**
     * Check if tool exists
     *
     * @param name Tool name
     * @return true if tool is registered
     */
    fun hasTool(name: String): Boolean {
        return tools.containsKey(name)
    }

    /**
     * Get tool names
     *
     * @return List of all tool names
     */
    fun getToolNames(): List<String> {
        return tools.keys.toList()
    }

    /**
     * Unregister a tool (for testing)
     *
     * @param name Tool name
     * @return true if tool was removed
     */
    fun unregister(name: String): Boolean {
        val removed = tools.remove(name) != null
        if (removed) {
            logger.info { "Unregistered tool: $name" }
        }
        return removed
    }

    /**
     * Clear all tools (for testing)
     */
    fun clear() {
        val count = tools.size
        tools.clear()
        logger.info { "Cleared $count tools from registry" }
    }

    /**
     * Get tool count
     */
    fun size(): Int {
        return tools.size
    }

    /**
     * Convert tool name to SubtaskKind.
     *
     * Uses convention: tool.name "read_file" → SubtaskKind.READ_FILE
     * Falls back to PLAN_STEP if tool not found or no matching SubtaskKind.
     *
     * @param toolName Tool name (e.g., "read_file", "code_editing")
     * @return SubtaskKind enum value
     */
    fun toSubtaskKind(toolName: String): SubtaskKind {
        // Check if tool exists in registry
        if (!hasTool(toolName)) {
            logger.warn { "Tool not found in registry: $toolName, using PLAN_STEP" }
            return SubtaskKind.PLAN_STEP
        }

        // Try to convert tool name to SubtaskKind using naming convention
        val enumName = toolName.uppercase()
        return try {
            SubtaskKind.valueOf(enumName)
        } catch (e: IllegalArgumentException) {
            logger.warn { "No SubtaskKind for tool: $toolName (tried $enumName), using PLAN_STEP" }
            SubtaskKind.PLAN_STEP
        }
    }

    /**
     * Check if tool name has a corresponding SubtaskKind.
     *
     * @param toolName Tool name
     * @return true if valid SubtaskKind exists for this tool
     */
    fun hasSubtaskKind(toolName: String): Boolean {
        val enumName = toolName.uppercase()
        return try {
            SubtaskKind.valueOf(enumName)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    companion object {
        /**
         * Reset instance (for testing)
         *
         * @param registry ToolRegistry instance to clear
         */
        fun resetInstance(registry: ToolRegistry) {
            registry.clear()
        }
    }
}
