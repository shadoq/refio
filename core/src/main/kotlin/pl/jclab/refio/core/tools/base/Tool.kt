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
     * One-line hint used to build the dynamic tool-selection matrix in system prompts.
     * Acts as a "row" in the When-to-use-what table: a short phrase describing when to
     * pick this tool over alternatives. Null means the tool is omitted from the matrix.
     *
     * Keep it under ~140 chars. Example: "Small new files (configs, stubs). For >50 lines prefer advance_code_editing."
     */
    val selectionHint: String?
        get() = null

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
 * Provider-agnostic tool schema for native function-calling APIs.
 *
 * Each adapter converts this to its provider-specific format:
 * - OpenAI/Ollama: {"type":"function","function":{"name":...,"description":...,"parameters":...}}
 * - Anthropic:     {"name":...,"description":...,"input_schema":...}
 *
 * parametersJsonSchema must be a valid JSON Schema draft-7 subset accepted by all providers:
 *   {"type":"object","properties":{...},"required":[...]}
 */
data class ToolSchema(
    val name: String,
    val description: String,
    val parametersJsonSchema: Map<String, Any>,
)

/**
 * Convert a Tool into a provider-agnostic schema using Tool.getParameterSchema().
 *
 * Wraps bare-properties maps in {"type":"object","properties":...} so all providers
 * accept the result. Tools returning emptyMap() become a no-parameter schema.
 */
fun Tool.toToolSchema(): ToolSchema {
    val schema = getParameterSchema()
    val resolved: Map<String, Any> = when {
        schema.isEmpty() -> mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        !schema.containsKey("type") -> mapOf(
            "type" to "object",
            "properties" to schema,
            "required" to emptyList<String>()
        )
        else -> schema
    }
    return ToolSchema(
        name = name,
        description = description,
        parametersJsonSchema = resolved,
    )
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
 * Structured summary of file modifications produced by a write tool.
 *
 * Lets the agent see *what* changed without re-reading the file.
 * Populated by FILE_MODIFYING / FILE_PRODUCING tools.
 */
data class ChangeSummary(
    /** Number of lines added (per "+" lines in unified diff). */
    val addedLines: Int,
    /** Number of lines removed (per "-" lines in unified diff). */
    val removedLines: Int,
    /** Unified diff (Myers algorithm, 3 lines context). Null for create-from-empty. */
    val unifiedDiff: String? = null,
    /** SHA-256 hash of file content before edit. Null if file did not exist. */
    val oldHash: String? = null,
    /** SHA-256 hash of file content after edit. */
    val newHash: String? = null,
    /** Number of replacements applied (for search-and-replace tools). */
    val replacements: Int? = null,
    /** Whether the file was newly created (vs. modified in place). */
    val created: Boolean = false
) {
    /**
     * True when an edit ran but produced no content change (identical before/after
     * content on an existing file). The tool still reports success=true (no I/O error),
     * but this flag lets callers and the agent distinguish a genuine edit from a
     * silent no-op — typically the LLM editor returned unchanged content, a search
     * pattern did not match, or the edit_description was too vague to apply.
     */
    val noop: Boolean
        get() = !created && addedLines == 0 && removedLines == 0
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
    val metadata: Map<String, Any>? = null,

    /**
     * Concrete next steps the agent should consider after this result.
     * Populated for empty-result, partial-result, or recoverable-error paths so the
     * agent does not need to guess (e.g. ["Try a less specific pattern", "Use file_search to locate the file"]).
     */
    val nextActionHints: List<String>? = null,

    /**
     * Recovery instruction for failed executions.
     * Explains *what to do next* in plain language. Always populated together with `error`
     * for recoverable failures (file not found, string not unique, parse failure, etc.).
     */
    val recovery: String? = null,

    /**
     * Structured summary of file changes (added/removed lines, diff, hashes).
     * Populated by FILE_MODIFYING / FILE_PRODUCING tools so the agent can reason about
     * what changed without re-reading the file.
     */
    val changeSummary: ChangeSummary? = null
) {
    companion object {
        /**
         * Create error result with optional recovery instruction and next-step hints.
         */
        fun error(
            message: String,
            recovery: String? = null,
            nextActionHints: List<String>? = null
        ): ToolResult {
            return ToolResult(
                success = false,
                error = message,
                recovery = recovery,
                nextActionHints = nextActionHints
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
