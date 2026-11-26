package pl.jclab.refio.core.api.modules

import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.project.ProjectHandle
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.tools.base.ToolFactory
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.security.FileLimits
import pl.jclab.refio.core.db.repositories.TaskRepository

/**
 * Builds a **project-scoped** [CoreApiRouter] from an **app-scoped** parent.
 *
 * The parent router is created with no [projectRoot] (app/global router); when the
 * plugin opens a project, it calls this factory with the project path and receives
 * a project-scoped router that shares the same `LLMClient`, `ConfigService`,
 * `PromptsService`, and `TaskRepository`, but registers its own `ToolRegistry`
 * with the full set of tools loaded.
 *
 * Kept out of `CoreApiRouter` so the composition root stays focused on wiring the
 * current router's own services rather than cross-scope bootstrap.
 */
internal object ProjectRouterFactory {
    fun create(
        projectRoot: java.nio.file.Path,
        projectHandle: ProjectHandle?,
        platformProject: Any?,
        llmClient: LLMClient,
        configService: ConfigService,
        promptsService: PromptsService,
        taskRepository: TaskRepository,
        runConfigOverrides: Map<String, String> = emptyMap(),
    ): CoreApiRouter {
        val toolRegistry = ToolRegistry()

        // Build the project-scoped router first so ToolFactory receives the project's own
        // ConfigService (.refio/config.yaml + PROJECT DB scope + runConfigOverrides), LLMClient,
        // and PromptsService instead of the app-level parent instances.
        val router = CoreApiRouter(
            toolRegistry = toolRegistry,
            projectRoot = projectRoot,
            platformProjectOverride = platformProject,
            projectHandle = projectHandle,
            runConfigOverrides = runConfigOverrides,
        )

        val maxFileSizeBytes = router.configService.getTyped(ConfigKeys.MAX_FILE_SIZE).toLong() * 1024 * 1024
        val fileLimits = FileLimits(maxFileSize = maxFileSizeBytes)

        val toolFactory = ToolFactory(
            projectRoot = projectRoot,
            toolRegistry = toolRegistry,
            llmClient = router.llmClient,
            configService = router.configService,
            promptsService = router.promptsService,
            taskRepository = router.taskRepository,
            fileLimits = fileLimits,
        )
        toolFactory.createAllTools().forEach { toolRegistry.register(it) }

        return router
    }
}
