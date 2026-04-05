package pl.jclab.refio.core.tools.base

/**
 * Base interface for all tools in the system.
 *
 * Tools are atomic operations that can be executed by the agent:
 * - File operations (read, write, search)
 * - Terminal commands
 * - Code editing
 * - Diff viewing
 *
 * All tools operate within a security sandbox and return structured results.
 */
interface Tool {
    /**
     * Unique tool identifier (e.g., "read_file", "run_terminal_command")
     */
    val name: String

    /**
     * Human-readable description of what the tool does
     */
    val description: String

    /**
     * Tool execution mode (READ_ONLY or WRITE)
     */
    val mode: ToolMode

    /**
     * Tool category - determines how outputs are used in context building
     */
    val category: ToolCategory

    /**
     * Origin of this tool (builtin, MCP, or plugin).
     */
    val origin: ToolOrigin
        get() = ToolOrigin.BUILTIN

    /**
     * Execute the tool with given parameters.
     *
     * @param params Tool-specific parameters as key-value map
     * @return ToolResult containing success status, output, and metadata
     */
    suspend fun execute(params: Map<String, Any>): ToolResult

    /**
     * Validate parameters before execution.
     * Override to add custom validation logic.
     *
     * @param params Parameters to validate
     * @throws IllegalArgumentException if validation fails
     */
    fun validateParams(params: Map<String, Any>) {
        // Default implementation: no validation
    }

    /**
     * Get schema describing expected parameters.
     * Used for documentation and LLM tool calling.
     *
     * @return JSON schema as map
     */
    fun getParameterSchema(): Map<String, Any> {
        return emptyMap()
    }
}

/**
 * Origin of a tool — where it was registered from.
 */
enum class ToolOrigin {
    BUILTIN,
    MCP,
    PLUGIN
}

/**
 * Tool execution mode
 */
enum class ToolMode {
    /**
     * Read-only tool (safe for PLAN mode)
     */
    READ_ONLY,

    /**
     * Write tool (requires AGENT mode)
     */
    WRITE
}

/**
 * Tool category - determines how outputs are used in context building
 */
enum class ToolCategory {
    /**
     * Tools that produce data needed by subsequent steps
     * (e.g., file_search, grep_search, read_file, view_diff)
     * Full outputs should be included in context for next LLM step
     */
    DATA_PRODUCING,

    /**
     * Tools that modify files
     * (e.g., code_editing, create_file, multi_edit)
     * Only affected files list should be tracked
     */
    FILE_MODIFYING,

    /**
     * Tools that create/regenerate files and produce output data
     * (e.g., advance_code_editing)
     * Like FILE_MODIFYING but output (diff, content) should be preserved in context
     * so the LLM doesn't need to re-read the file it just created.
     */
    FILE_PRODUCING,

    /**
     * Tools that execute commands
     * (e.g., run_terminal_command)
     * Mixed behavior - may produce data or modify files
     */
    EXECUTION,

    /**
     * Tools that manage internal agent state (plans, memory, messages, subagents).
     * Do not modify user files. Treated as sequential by ParallelToolExecutor
     * despite being READ_ONLY in filesystem terms.
     */
    SYSTEM
}

/**
 * Result of tool execution
 */
data class ToolResult(
    /**
     * Whether execution was successful
     */
    val success: Boolean,

    /**
     * Tool output (file contents, command output, etc.)
     */
    val output: String? = null,

    /**
     * Error message if execution failed
     */
    val error: String? = null,

    /**
     * Bytes read (for file operations)
     */
    val bytesRead: Int? = null,

    /**
     * Bytes written (for file operations)
     */
    val bytesWritten: Int? = null,

    /**
     * Execution duration in milliseconds
     */
    val durationMs: Int? = null,

    /**
     * Exit code (for terminal commands)
     */
    val exitCode: Int? = null,

    /**
     * Files changed (for multi-file operations)
     */
    val filesChanged: List<String>? = null,

    /**
     * Additional metadata
     */
    val metadata: Map<String, Any>? = null
) {
    companion object {
        /**
         * Create error result
         */
        fun error(message: String): ToolResult {
            return ToolResult(
                success = false,
                error = message
            )
        }

        /**
         * Create success result with output
         */
        fun success(output: String, metadata: Map<String, Any>? = null): ToolResult {
            return ToolResult(
                success = true,
                output = output,
                metadata = metadata
            )
        }
    }
}
