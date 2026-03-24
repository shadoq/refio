package pl.jclab.refio.cli

import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.context.ContextProviderRegistry
import pl.jclab.refio.core.context.mcp.MCPManager
import pl.jclab.refio.core.context.providers.ClipboardContextProvider
import pl.jclab.refio.core.context.providers.UrlContextProvider
import pl.jclab.refio.core.context.providers.standalone.*
import pl.jclab.refio.core.project.StandaloneProjectHandle
import pl.jclab.refio.core.utils.ProjectIdGenerator
import pl.jclab.refio.core.logging.dualLogger
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
    private val projectPath: Path
) {
    private var appRouter: CoreApiRouter? = null
    private var projectRouter: CoreApiRouter? = null

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

        // 1. Database path: <project>/.refio/database.sqlite
        val refioDir = absolutePath.resolve(".refio").toFile()
        if (!refioDir.exists()) refioDir.mkdirs()
        val dbPath = refioDir.resolve("database.sqlite").absolutePath

        // 2. App-level router (no tools, no project root — shared services)
        val appRouter = CoreApiRouter(toolRegistry = null, projectRoot = null)
        appRouter.initialize(dbPath)
        this.appRouter = appRouter

        // 3. Initialize provider API keys from database → System properties
        appRouter.initializeProviderKeys()

        // 4. Load YAML config if database is empty
        appRouter.getConfigService().loadFromYamlIfMissing()
        appRouter.getConfigService().initializeDefaults()

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
            ideProject = null
        )
        projectRouter.initialize(dbPath)
        this.projectRouter = projectRouter

        // 7. MCP manager (optional, may fail if no configs)
        try {
            val projectId = ProjectIdGenerator.generate(absolutePath)
            MCPManager.initialize(projectId, null)
        } catch (e: Exception) {
            logger.warn { "MCP initialization skipped: ${e.message}" }
        }

        logger.info { "Standalone core initialized" }
        return projectRouter
    }

    /**
     * Gracefully shut down the core layer.
     */
    fun shutdown() {
        logger.info { "Shutting down standalone core" }
        projectRouter = null
        appRouter = null
    }
}
