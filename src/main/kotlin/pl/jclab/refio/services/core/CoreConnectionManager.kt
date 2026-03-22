package pl.jclab.refio.services.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.application.PathManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolFactory
import pl.jclab.refio.core.context.ContextProviderRegistry
import pl.jclab.refio.core.context.mcp.MCPManager
import pl.jclab.refio.core.utils.ProjectIdGenerator
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.tools.security.FileLimits
import java.io.File
import java.nio.file.Paths

/**
 * Core health state
 */
enum class CoreHealthState {
    CONNECTED,
    DEGRADED,
    DISCONNECTED
}

/**
 * Core health data
 */
data class CoreHealth(
    val state: CoreHealthState,
    val latencyMs: Int? = null,
    val port: Int = 0,
    val lastCheck: Long = System.currentTimeMillis()
)

/**
 * Application-level service managing embedded Kotlin core
 *
 * NOTE: Migrated from HTTP-based Python core to in-process Kotlin core.
 * No HTTP calls, no health checks needed - core runs in-process.
 */
@Service(Service.Level.APP)
class CoreConnectionManager {
    private val logger = dualLogger("CoreConnectionManager")

    // Use proper coroutine scope for Application-level service
    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Always CONNECTED since core is in-process
    private val _healthState = MutableStateFlow(CoreHealth(CoreHealthState.CONNECTED, latencyMs = 0))
    val healthState: StateFlow<CoreHealth> = _healthState.asStateFlow()

    private val router: CoreApiRouter

    // Cache for project-specific routers (one per project)
    private val projectRouters = mutableMapOf<String, CoreApiRouter>()

    init {
        logger.info { "Initializing embedded Kotlin core" }

        try {
            // Initialize CoreApiRouter WITHOUT ToolRegistry (will be provided per-project)
            // ToolRegistry is project-specific and will be created by SessionManager
            router = CoreApiRouter(toolRegistry = null, projectRoot = null)

            // Initialize database
            val dbPath = getDbPath()
            logger.info { "Database path: $dbPath" }
            router.initialize(dbPath)

            // Initialize provider API keys from database to System properties
            logger.info { "Initializing provider API keys" }
            router.initializeProviderKeys()

            // Load configuration from YAML file (if exists and DB is empty)
            logger.info { "Loading configuration from YAML file (if missing)" }
            router.configService.loadFromYamlIfMissing()

            // Initialize default configuration values (if missing after YAML load)
            logger.info { "Initializing default configuration values" }
            router.configService.initializeDefaults()

            // Initialize Context Provider Registry
            logger.info { "Initializing Context Provider Registry" }
            ContextProviderRegistry.initialize()

            // Initialize MCP Manager
            logger.info { "Initializing MCP Manager" }
            MCPManager.initialize()

            logger.info { "Embedded core initialized successfully (without ToolRegistry)" }
            logger.warn { "ToolRegistry should be initialized per-project by SessionManager" }

            // Update health state to CONNECTED
            _healthState.value = CoreHealth(
                state = CoreHealthState.CONNECTED,
                latencyMs = 0,
                port = 0 // No port for in-process calls
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize embedded core" }
            _healthState.value = CoreHealth(
                state = CoreHealthState.DISCONNECTED,
                latencyMs = null
            )
            throw e
        }
    }

    /**
     * Get database path for storing plugin data
     */
    private fun getDbPath(): String {
        val systemPath = PathManager.getSystemPath()
        val dbDir = File(systemPath, "refio")

        // Create directory if it doesn't exist
        if (!dbDir.exists()) {
            dbDir.mkdirs()
            logger.info { "Created database directory: ${dbDir.absolutePath}" }
        }

        return File(dbDir, "refio.db").absolutePath
    }

    /**
     * Get the base API router for making in-process API calls.
     *
     * NOTE: This router doesn't have ToolRegistry or projectRoot.
     * For project-specific operations, create a new CoreApiRouter with
     * project-specific ToolRegistry and projectRoot.
     */
    fun getApiRouter(): CoreApiRouter {
        return router
    }

    /**
     * Get or create a cached project-specific API router with ToolRegistry.
     * Uses caching to avoid recreating routers and re-initializing database.
     *
     * @param projectRoot Project root directory (from project.basePath)
     * @return CoreApiRouter configured for the project (cached)
     */
    fun getOrCreateProjectRouter(
        projectRoot: java.nio.file.Path,
        ideProject: com.intellij.openapi.project.Project? = null
    ): CoreApiRouter {
        val absolutePath = projectRoot.toAbsolutePath().toString()

        // Return cached router if exists
        projectRouters[absolutePath]?.let { cachedRouter ->
            logger.debug { "Using cached router for project: $absolutePath" }
            if (ideProject != null && !cachedRouter.hasIdeProject()) {
                logger.info { "Recreating cached router with IDE project for: $absolutePath" }
                val refreshedRouter = createProjectRouterInternal(projectRoot, ideProject)
                projectRouters[absolutePath] = refreshedRouter
                val projectId = ProjectIdGenerator.generate(projectRoot)
                MCPManager.setToolRegistry(projectId, refreshedRouter.getToolRegistry())
                return refreshedRouter
            }
            // Ensure MCPManager has ToolRegistry even for cached router
            // (in case it was initialized without ToolRegistry from UI)
            val projectId = ProjectIdGenerator.generate(projectRoot)
            MCPManager.setToolRegistry(projectId, cachedRouter.getToolRegistry())
            return cachedRouter
        }

        // Create new router and cache it
        logger.info { "Creating new router for project: $absolutePath" }
        val projectRouter = createProjectRouterInternal(projectRoot, ideProject)
        projectRouters[absolutePath] = projectRouter
        return projectRouter
    }

    /**
     * Create a project-specific API router with ToolRegistry.
     *
     * DEPRECATED: Use getOrCreateProjectRouter() instead to avoid recreating routers.
     *
     * @param projectRoot Project root directory (from project.basePath)
     * @return CoreApiRouter configured for the project
     */
    @Deprecated("Use getOrCreateProjectRouter() instead", ReplaceWith("getOrCreateProjectRouter(projectRoot, ideProject)"))
    fun createProjectRouter(
        projectRoot: java.nio.file.Path,
        ideProject: com.intellij.openapi.project.Project? = null
    ): CoreApiRouter {
        return createProjectRouterInternal(projectRoot, ideProject)
    }

    /**
     * Internal method to create a project-specific API router.
     */
    private fun createProjectRouterInternal(
        projectRoot: java.nio.file.Path,
        ideProject: com.intellij.openapi.project.Project? = null
    ): CoreApiRouter {
        logger.info { "Creating project-specific router for projectRoot=$projectRoot (absolute=${projectRoot.toAbsolutePath()})" }

        // Create NEW project-specific ToolRegistry (not shared)
        val toolRegistry = ToolRegistry()
        logger.info { "Created new ToolRegistry for project: $projectRoot" }
        val projectId = ProjectIdGenerator.generate(projectRoot)

        // Get required services from base router
        val llmClient = router.llmClient
        val configService = router.configService
        val promptsService = router.promptsService
        val taskRepository = router.taskRepository

        val maxFileSizeBytes = configService.getTyped(ConfigKeys.MAX_FILE_SIZE).toLong() * 1024 * 1024
        val fileLimits = FileLimits(maxFileSize = maxFileSizeBytes)

        val toolFactory = ToolFactory(
            projectRoot = projectRoot,
            toolRegistry = toolRegistry,
            llmClient = llmClient,
            configService = configService,
            promptsService = promptsService,
            taskRepository = taskRepository,
            fileLimits = fileLimits
        )
        val tools = toolFactory.createAllTools()

        tools.forEach { tool ->
            toolRegistry.register(tool)
            logger.info { "Registered tool for project: ${tool.name} (${tool.mode})" }
        }

        // Create new router with ToolRegistry, projectRoot, and ideProject
        val projectRouter = CoreApiRouter(toolRegistry, projectRoot, ideProject)

        // Initialize with same database
        val dbPath = getDbPath()
        projectRouter.initialize(dbPath)

        logger.info { "Project-specific router created with ${toolRegistry.size()} tools" }

        // Initialize MCP for this project (loads configs, registers providers/tools)
        MCPManager.initialize(projectId, toolRegistry)
        return projectRouter
    }

    /**
     * Get the database path
     */
    fun getDatabasePath(): String {
        return getDbPath()
    }

    /**
     * Re-synchronize provider API keys from database to System properties.
     * Call this after updating API keys in Settings UI to ensure they're immediately available.
     *
     * Note: This is also called automatically by CoreApiRouter.updateConfig() when provider
     * settings are updated, but this method provides explicit control when needed.
     */
    fun resyncProviderKeys() {
        logger.info { "Re-synchronizing provider API keys from database" }
        router.initializeProviderKeys()
        logger.info { "Provider API keys re-synchronized" }
    }

    /**
     * Dispose resources
     */
    fun dispose() {
        cs.cancel()
    }

    companion object {
        fun getInstance(): CoreConnectionManager {
            return com.intellij.openapi.components.service()
        }
    }
}
