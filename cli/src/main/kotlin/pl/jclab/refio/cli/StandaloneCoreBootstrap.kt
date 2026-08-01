package pl.jclab.refio.cli

import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.config.RefioHome
import pl.jclab.refio.core.context.ContextProviderRegistry
import pl.jclab.refio.core.context.mcp.MCPManager
import pl.jclab.refio.core.context.mcp.MCPServerConfig
import pl.jclab.refio.core.context.providers.ClipboardContextProvider
import pl.jclab.refio.core.context.providers.UrlContextProvider
import pl.jclab.refio.core.context.providers.standalone.*
import pl.jclab.refio.core.project.StandaloneProjectHandle
import pl.jclab.refio.core.utils.ProjectIdGenerator
import pl.jclab.refio.core.logging.dualLogger
import java.io.File
import java.nio.file.Path

private val logger = dualLogger("StandaloneCoreBootstrap")

/**
 * Initializes the Refio core layer without IntelliJ Platform SDK.
 *
 * This is the standalone counterpart of CoreConnectionManager.
 * It performs the same two-tier initialization:
 * 1. App-level router (shared database, config, LLM client)
 * 2. Project-level router (project-specific tools, context)
 *
 * Usage:
 * ```
 * val bootstrap = StandaloneCoreBootstrap(projectPath)
 * val router = bootstrap.initialize()
 * // ... use router ...
 * bootstrap.shutdown()
 * ```
 */
class StandaloneCoreBootstrap(
    private val projectPath: Path,
    /**
     * Run-scope config overrides from `--config` / `--config-file`. Threaded into the
     * app router and forwarded to the project router. Highest priority, read-only, never persisted.
     */
    private val runConfigOverrides: Map<String, String> = emptyMap(),
    /**
     * MCP servers declared for this run only (`--mcp-server`). Connected like stored servers but
     * never written to the database, so a test run leaves no server behind.
     */
    private val runScopeMcpServers: List<MCPServerConfig> = emptyList()
) {
    private var appRouter: CoreApiRouter? = null
    private var projectRouter: CoreApiRouter? = null
    private var mcpProjectId: String? = null

    val router: CoreApiRouter
        get() = projectRouter ?: throw IllegalStateException("Not initialized. Call initialize() first.")

    /**
     * Initialize the core layer: database, config, tools, context providers.
     *
     * Mirrors CoreConnectionManager initialization sequence:
     * 1. Create app-level router (shared services)
     * 2. Initialize database
     * 3. Load config from YAML
     * 4. Initialize context providers (non-IDE)
     * 5. Create project-level router with tools
     */
    fun initialize(): CoreApiRouter {
        val absolutePath = projectPath.toAbsolutePath().normalize()
        logger.info { "Initializing standalone core for project: $absolutePath" }

        val projectHandle = StandaloneProjectHandle(absolutePath)

        // Ensure project .refio dir exists for project-local config files
        val refioDir = absolutePath.resolve(".refio").toFile()
        if (!refioDir.exists()) refioDir.mkdirs()

        // 1. Database path: <refio home>/data/database.sqlite (shared across projects)
        val refioDataDir = RefioHome.resolve("data").toFile()
        if (!refioDataDir.exists()) refioDataDir.mkdirs()
        val dbPath = File(refioDataDir, "database.sqlite").absolutePath

        // 2. App-level router (no tools, no project root — shared services).
        //    Run-scope overrides flow here and are forwarded to the project router below.
        val appRouter = CoreApiRouter(
            toolRegistry = null,
            projectRoot = null,
            runConfigOverrides = runConfigOverrides
        )
        appRouter.initialize(dbPath)
        this.appRouter = appRouter

        // 3. Initialize provider API keys from database → System properties
        appRouter.configRouter.initializeProviderKeys()

        // 4. Load YAML config if database is empty
        appRouter.configService.loadFromYamlIfMissing()
        appRouter.configService.initializeDefaults()

        // 5. Context providers — non-IDE subset only
        //    Note: Most providers (GitDiff, GitCommit, File, Folder, etc.) depend on
        //    IntelliJ APIs and are excluded from the :core module.
        //    Only ClipboardContextProvider and UrlContextProvider are available.
        ContextProviderRegistry.providerFactory = { _ ->
            buildList {
                add(ClipboardContextProvider())
                add(UrlContextProvider())
                // Standalone providers — no IntelliJ dependency
                add(StandaloneFileContextProvider())
                add(StandaloneFolderContextProvider())
                add(StandaloneGitDiffContextProvider())
                add(StandaloneGitCommitContextProvider())
                add(StandaloneGrepSearchContextProvider())
                add(StandaloneCodebaseContextProvider())
                add(StandaloneDocsContextProvider())
            }
        }
        ContextProviderRegistry.initialize(isIdeEnvironment = false)

        // 6. Project-level router with tools (via CoreApiRouter.createProjectRouter)
        val projectRouter = appRouter.createProjectRouter(
            projectRoot = absolutePath,
            projectHandle = projectHandle,
            platformProject = null
        )
        projectRouter.initialize(dbPath)
        this.projectRouter = projectRouter

        // 7. MCP manager (optional, may fail if no configs).
        //    Pass the project ToolRegistry so TOOLS-mode MCP servers actually register their
        //    tools for the agent — without it the CLI connected servers but never exposed tools.
        try {
            val projectId = ProjectIdGenerator.generate(absolutePath)
            mcpProjectId = projectId
            MCPManager.initialize(projectId, projectRouter.getToolRegistry(), runScopeMcpServers)
        } catch (e: Exception) {
            logger.warn { "MCP initialization skipped: ${e.message}" }
        }

        logger.info { "Standalone core initialized" }
        return projectRouter
    }

    /**
     * Waits until the MCP servers finish connecting, so their tools are in the registry before the
     * first LLM call builds its tool schema.
     *
     * [MCPManager.initialize] connects in the background. A long-lived IDE absorbs that, but a
     * headless run starts its turn immediately: a local stdio server usually wins the race while an
     * HTTP or SSE one, paying for network round trips, does not - and the model then reports the
     * tool as unavailable. Bounded, and a server that never settles only costs the timeout.
     */
    suspend fun awaitMcpReady(timeoutMs: Long = MCP_READY_TIMEOUT_MS) {
        val projectId = mcpProjectId ?: return
        val serverIds = MCPManager.getAllServers(projectId).map { it.id }
        if (serverIds.isEmpty()) return
        McpProbe.awaitSettled(projectId, serverIds, timeoutMs)
        logger.info {
            "MCP settled before turn: " + serverIds.joinToString {
                "$it=${MCPManager.getServerStatus(projectId, it)}"
            }
        }
    }

    /**
     * Gracefully shut down the core layer, releasing all resources.
     */
    fun shutdown() {
        logger.info { "Shutting down standalone core" }
        val localProjectRouter = projectRouter
        val localAppRouter = appRouter

        try {
            MCPManager.shutdown()
        } catch (e: Exception) {
            logger.warn { "Error shutting down MCP: ${e.message}" }
        }

        runCatching { localProjectRouter?.close() }
            .onFailure { logger.warn { "Error closing project router: ${it.message}" } }
        runCatching { localAppRouter?.close() }
            .onFailure { logger.warn { "Error closing app router: ${it.message}" } }

        projectRouter = null
        appRouter = null

        logger.info { "Standalone core shut down" }
    }

    private companion object {
        /** Enough for an HTTP/SSE handshake; a dead server only costs this once. */
        const val MCP_READY_TIMEOUT_MS = 20_000L
    }
}
