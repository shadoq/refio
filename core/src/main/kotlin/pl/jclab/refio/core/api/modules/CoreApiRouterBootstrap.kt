package pl.jclab.refio.core.api.modules

import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.config.RefioHome
import pl.jclab.refio.core.db.DatabaseFactory
import pl.jclab.refio.core.services.OllamaRequestGate
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.AgentTurnLoop
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.maintenance.LogRetentionService
import pl.jclab.refio.core.services.maintenance.TaskReconciler
import pl.jclab.refio.core.tools.implementations.RagSearchTool
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot bootstrap side-effects for [CoreApiRouter] that don't belong in
 * the composition root itself:
 *
 *  - [registerSystemTools] — installs `invoke_subagent`, `delegate_to_strong_model`,
 *    `tasks`, `memory`, `manage_subagent`, `send_message` into a project-scoped
 *    router's [pl.jclab.refio.core.tools.base.ToolRegistry]. No-op when
 *    `toolRegistry` or `projectRoot` is absent (app-level router).
 *  - [applyOllamaConcurrency] — sets the `OllamaRequestGate` global tuned from
 *    `providers.ollama_max_concurrent`.
 *  - [initializeCore] — database init, prompt defaults, and RAG tool wiring.
 *
 * Kept as free functions so `CoreApiRouter.init {}` and `initialize()` stay
 * short enough to read at a glance.
 */
internal object CoreApiRouterBootstrap {
    private val logger = dualLogger("CoreApiRouterBootstrap")

    /** Every router of this JVM shares one database, so the repair after a crash runs once. */
    private val reconciled = AtomicBoolean(false)

    /** One retention schedule per JVM, for the same reason. */
    private val retentionStarted = AtomicBoolean(false)

    private const val RETENTION_INTERVAL_HOURS = 12L

    fun registerSystemTools(router: CoreApiRouter) {
        val toolRegistry = router.toolRegistryOrNull ?: return
        val projectRoot = router.projectRootOrNull ?: return

        SystemToolsRegistrar(
            configService = router.configService,
            llmClient = router.llmClient,
            agentPlanService = router.agentPlanService,
            workingMemoryService = router.workingMemoryServiceInternal,
            subtaskRepository = router.persistenceInternal.subtaskRepository,
            agentEventBus = router.agentEventBus,
            agentInboxRegistry = router.agentInboxRegistry,
            subagentRouterProvider = { router.subagentRouter },
            runTurnCallback = { request, listener, stream ->
                router.agentRouter.runTurn(
                    request = request,
                    streamCallback = stream,
                    listener = listener,
                )
            },
        ).register(toolRegistry)
    }

    fun applyOllamaConcurrency(configService: ConfigService) {
        val value = configService.get(ConfigKeys.OLLAMA_MAX_CONCURRENT.key)?.toIntOrNull()
            ?.takeIf { it > 0 } ?: return
        OllamaRequestGate.maxConcurrentPerEndpoint = value
        logger.info { "CoreApiRouter: Ollama maxConcurrent=$value" }
    }

    fun applyProviderConcurrency(configService: ConfigService) {
        val value = configService.get(ConfigKeys.PROVIDER_MAX_CONCURRENT.key)?.toIntOrNull()
            ?.takeIf { it > 0 } ?: return
        pl.jclab.refio.core.services.ProviderRequestGate.maxConcurrentPerProvider = value
        logger.info { "CoreApiRouter: per-provider maxConcurrent=$value" }
    }

    fun initializeCore(router: CoreApiRouter, dbPath: String) {
        logger.info { "Initializing core with dbPath=$dbPath" }
        DatabaseFactory.init(dbPath)
        reconcileOnce(router)
        startLogRetentionOnce(router, dbPath)
        router.promptsService.initializeDefaults()
        // Project settings are stored per project, so this is the first point where they can be
        // applied - the app-level router has no project to apply them for. No-op without one.
        router.configService.materializeProjectConfig()

        val toolRegistry = router.toolRegistryOrNull ?: return
        val projectRoot = router.projectRootOrNull ?: return
        val ragSearchService = router.ragSearchServiceInternal ?: return

        try {
            val (providerId, modelId) = router.embeddingProviderFactoryInternal.resolve(
                router.configService.getEmbeddingModel()
            )
            toolRegistry.register(
                RagSearchTool(
                    ragSearchService = ragSearchService,
                    embeddingModel = modelId,
                    projectRoot = projectRoot,
                )
            )
            logger.info { "Registered rag_search tool (model=$modelId, provider=$providerId)" }
        } catch (e: IllegalArgumentException) {
            logger.debug { "rag_search tool already registered" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to register rag_search tool: ${e.message}" }
        }
    }

    /**
     * Repairs the tasks a stopped process left as running (closed IDE, Ctrl+C, crash), so the user
     * can continue them; tasks of processes that still run are left alone.
     */
    private fun reconcileOnce(router: CoreApiRouter) {
        if (!reconciled.compareAndSet(false, true)) {
            return
        }
        val persistence = router.persistenceInternal
        runCatching {
            TaskReconciler(
                taskStateRepository = persistence.taskStateRepository,
                taskRepository = persistence.taskRepository,
                subtaskRepository = persistence.subtaskRepository,
                chatMessageRepository = persistence.chatMessageRepository,
            ).reconcile()
        }.onSuccess { repaired ->
            if (repaired > 0) {
                logger.info { "Repaired $repaired tasks left running by a stopped process" }
            }
        }.onFailure { logger.warn(it) { "Could not repair tasks left running by a stopped process" } }
    }

    /**
     * Deletes logs older than `logs.retention_days` now and then every 12 hours, on a background
     * thread so start-up does not wait for it. Only for the database in the Refio home: a test or
     * scratch database passed by path is left alone. Rolled CLI log files are looked for in the
     * same home, so a run with its own home never touches the files of the real one.
     */
    private fun startLogRetentionOnce(router: CoreApiRouter, dbPath: String) {
        val home = RefioHome.resolve().toAbsolutePath().normalize()
        if (!Path.of(dbPath).toAbsolutePath().normalize().startsWith(home)) {
            return
        }
        if (!retentionStarted.compareAndSet(false, true)) {
            return
        }
        val persistence = router.persistenceInternal
        val retention = LogRetentionService(
            apiLogRepository = persistence.apiLogRepository,
            agentEventRepository = persistence.agentEventSqlRepository,
            logDir = home,
            retentionDays = { router.configService.getTyped(ConfigKeys.LOGS_RETENTION_DAYS) },
        )
        val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "refio-log-retention").apply { isDaemon = true }
        }
        scheduler.scheduleWithFixedDelay(
            { runCatching { retention.sweep() }.onFailure { logger.warn(it) { "Log retention sweep failed" } } },
            0,
            RETENTION_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
    }
}
