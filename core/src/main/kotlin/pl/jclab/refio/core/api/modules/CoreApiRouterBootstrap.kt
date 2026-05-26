package pl.jclab.refio.core.api.modules

import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.DatabaseFactory
import pl.jclab.refio.core.services.OllamaRequestGate
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.AgentTurnLoop
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.tools.implementations.RagSearchTool

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

    fun initializeCore(router: CoreApiRouter, dbPath: String) {
        logger.info { "Initializing core with dbPath=$dbPath" }
        DatabaseFactory.init(dbPath)
        router.promptsService.initializeDefaults()

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
}
