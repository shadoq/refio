package pl.jclab.refio.core.tools.base

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.security.NetworkPolicy
import pl.jclab.refio.core.security.UrlPolicy
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ProcessManager
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.services.turn.UserQuestionService
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.implementations.*
import pl.jclab.refio.core.tools.refactor.StructuralRefactorer
import pl.jclab.refio.core.tools.refactor.TextStructuralRefactorer
import pl.jclab.refio.core.tools.security.CommandLimits
import pl.jclab.refio.core.tools.security.CommandRuleDefaults
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
    private val userQuestionService: UserQuestionService = UserQuestionService(),
    private val fileLimits: FileLimits = FileLimits.DEFAULT,
    private val commandLimits: CommandLimits = CommandLimits.DEFAULT,
    structuralRefactorer: StructuralRefactorer? = null
) {
    init {
        logger.info { "ToolFactory initializing with projectRoot=$projectRoot (absolute=${projectRoot.toAbsolutePath()})" }
    }

    private val sandbox = PathSandbox.withConfig(projectRoot, configService)
    // Structural refactoring engine: the IntelliJ plugin injects a PSI-backed implementation;
    // CLI/headless falls back to the word-boundary text engine.
    private val refactorer = structuralRefactorer ?: TextStructuralRefactorer(sandbox, fileLimits)
    private val registry = toolRegistry
    // Owned by the registry, not by this factory: the factory is discarded right after it fills the
    // registry, while the background processes (and the reaper thread behind them) have to live as
    // long as the tools do and be released with them.
    private val processManager = ProcessManager().also { toolRegistry.addCloseable(it) }
    private val networkPolicy = NetworkPolicy(configService)
    // One SSRF guard shared by every outbound tool, so http_request and fetch_webpage can never
    // diverge. The loopback opt-in is resolved per call to honour run-scope config overrides.
    private val urlPolicy = UrlPolicy(
        allowLoopback = { runCatching { configService.getTyped(ConfigKeys.SECURITY_ALLOW_LOOPBACK) }.getOrDefault(false) }
    )

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
            ViewDiffTool(sandbox),

            // Reasoning slot (no-op, gives the model an explicit place to think
            // between tool calls — used as a loop-breaker and pre-action checkpoint)
            ThinkTool(),

            // Web tools
            WebSearchTool(configService, networkPolicy),
            FetchWebpageTool(llmClient, configService, networkPolicy, urlPolicy),

            // Code intelligence
            CodeIntelligenceTool(sandbox, fileLimits),
            FindUsagesTool(refactorer),

            // Process monitoring (read-only — only reads output)
            MonitorProcessTool(processManager),

            // User interaction
            AskUserTool(userQuestionService),

            // Utilities
            SleepTool()
        )
    }

    /**
     * Create write tools (requires AGENT mode)
     *
     * @return List of write tool instances
     */
    fun createWriteTools(): List<Tool> {
        return listOf(
            // File operations (write)
            CreateNewFileTool(sandbox, fileLimits),
            MultiLineEditorTool(sandbox, fileLimits, llmClient, configService, promptsService, taskRepository),
            AdvanceCodeEditingTool(sandbox, fileLimits, llmClient, configService, promptsService, taskRepository),
            CodeEditingTool(sandbox, fileLimits),

            // Batch operations
            MultiEditTool(sandbox, fileLimits),

            // Structural refactoring
            RenameSymbolTool(refactorer),

            // Terminal operations
            RunTerminalCommandTool(sandbox, commandLimits, CommandRuleDefaults.createDefaultMatcher()),

            // Network operations
            HttpRequestTool(sandbox = sandbox, networkPolicy = networkPolicy, urlPolicy = urlPolicy),

            // Code execution
            RunCodeTool(sandbox),

            // LLM call (raw single-turn call, no agent loop)
            LlmCallTool(llmClient, configService, sandbox, fileLimits),

            // Background process execution
            RunProcessBackgroundTool(sandbox, processManager, CommandRuleDefaults.createDefaultMatcher())
        )
    }
}
