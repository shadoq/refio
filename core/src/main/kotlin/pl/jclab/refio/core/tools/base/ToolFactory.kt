package pl.jclab.refio.core.tools.base

import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.implementations.*
import pl.jclab.refio.core.tools.security.CommandDenylist
import pl.jclab.refio.core.tools.security.CommandLimits
import pl.jclab.refio.core.tools.security.CommandWhitelist
import pl.jclab.refio.core.tools.security.FileLimits
import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Path

private val logger = dualLogger("ToolFactory")

/**
 * Factory for creating and registering tools.
 *
 * Handles:
 * - Tool instantiation with dependencies
 * - Tool registration in project-specific registry
 * - Configuration management
 */
class ToolFactory(
    private val projectRoot: Path,
    private val toolRegistry: ToolRegistry,
    private val llmClient: LLMClient,
    private val configService: ConfigService,
    private val promptsService: PromptsService,
    private val taskRepository: pl.jclab.refio.core.db.repositories.TaskRepository,
    private val fileLimits: FileLimits = FileLimits.DEFAULT,
    private val commandLimits: CommandLimits = CommandLimits.DEFAULT,
    private val commandDenylist: CommandDenylist = CommandDenylist.DEFAULT
) {
    init {
        logger.info { "ToolFactory initializing with projectRoot=$projectRoot (absolute=${projectRoot.toAbsolutePath()})" }
    }

    private val sandbox = PathSandbox.withConfig(projectRoot, configService)
    private val registry = toolRegistry

    /**
     * Create and register all available tools
     *
     * @return Number of tools registered
     */
    fun registerAllTools(): Int {
        val tools = createAllTools()

        tools.forEach { tool ->
            try {
                registry.register(tool)
            } catch (e: IllegalArgumentException) {
                logger.warn { "Tool already registered: ${tool.name}" }
            }
        }

        logger.info { "Registered ${tools.size} tools" }
        return tools.size
    }

    /**
     * Create all available tools
     *
     * @return List of all tool instances
     */
    fun createAllTools(): List<Tool> {
        return createReadOnlyTools() + createWriteTools()
    }

    /**
     * Create read-only tools (safe for PLAN/CHAT modes)
     *
     * @return List of read-only tool instances
     */
    fun createReadOnlyTools(): List<Tool> {
        return listOf(
            // File operations (read-only)
            ReadFileTool(sandbox, fileLimits),
            ReadDirectoryTool(sandbox, fileLimits),

            // Search operations
            FileSearchTool(sandbox, fileLimits),
            GrepSearchTool(sandbox, fileLimits),

            // Diff and comparison
            ViewDiffTool(sandbox)
        )
    }

    /**
     * Create write tools (requires AGENT mode)
     *
     * @return List of write tool instances
     */
    fun createWriteTools(): List<Tool> {
        val whitelistConfig = configService.getTerminalWhitelistConfig()
        val whitelist = CommandWhitelist(whitelistConfig, commandDenylist)

        return listOf(
            // File operations (write)
            CreateNewFileTool(sandbox, fileLimits),
            MultiLineEditorTool(sandbox, fileLimits, llmClient, configService, promptsService, taskRepository),
            AdvanceCodeEditingTool(sandbox, fileLimits, llmClient, configService, promptsService, taskRepository),
            CodeEditingTool(sandbox, fileLimits),

            // Batch operations
            MultiEditTool(sandbox, fileLimits),

            // Terminal operations
            RunTerminalCommandTool(sandbox, whitelist, commandLimits),

            // Network operations
            HttpRequestTool(sandbox),

            // Code execution
            RunCodeTool(sandbox)
        )
    }
}
