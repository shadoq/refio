package pl.jclab.refio.core.api.modules

import kotlinx.coroutines.CoroutineScope
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.ProjectAnalysisReportRepository
import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.core.db.repositories.SnapshotGroupRepository
import pl.jclab.refio.core.db.repositories.SnapshotRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.*
import pl.jclab.refio.core.services.analysis.*
import pl.jclab.refio.core.services.analysis.project.RichProjectAnalysisEngine
import pl.jclab.refio.core.services.context.WorkingMemoryService

/**
 * Bundles the "analysis stack" — embeddings, RAG chunking, file analyzer,
 * rich project analysis, project analyzer, context service and snapshot service.
 *
 * All fields are null when projectRoot is not provided (app-level router).
 * Extracted from CoreApiRouter.
 */
internal class AnalysisStack(
    projectRoot: java.nio.file.Path?,
    private val configService: ConfigService,
    private val ragRepository: RagRepository,
    snapshotRepository: SnapshotRepository,
    snapshotGroupRepository: SnapshotGroupRepository,
    analysisReportRepository: ProjectAnalysisReportRepository,
    taskRepository: TaskRepository,
    chatMessageRepository: ChatMessageRepository,
    subtaskRepository: SubtaskRepository,
    workingMemoryService: WorkingMemoryService,
    conversationSummaryService: ConversationSummaryService,
    scope: CoroutineScope,
    private val embeddingProviderFactory: EmbeddingProviderFactory,
    platformProject: Any? = null,
) {
    private val logger = dualLogger("AnalysisStack")

    private val embeddingProviderById: (providerId: String) -> EmbeddingProvider =
        { providerId -> embeddingProviderFactory.forProvider(providerId) }
    val languageAnalyzers: List<LanguageAnalyzer> = listOf(
        KotlinLanguageAnalyzer(),
        JavaLanguageAnalyzer(),
        PythonLanguageAnalyzer(),
        TypeScriptLanguageAnalyzer(),
        GoLanguageAnalyzer(),
        RustLanguageAnalyzer(),
        HtmlLanguageAnalyzer(),
        CppLanguageAnalyzer(),
        CssLanguageAnalyzer()
    )

    val embeddingsService: EmbeddingsService? = if (projectRoot != null) {
        EmbeddingsService(
            configService = configService,
            providerFactory = embeddingProviderById
        )
    } else null

    val ragChunkingStrategy: ChunkingStrategy = when (ChunkingMode.fromConfig(configService.getTyped(ConfigKeys.RAG_CHUNKING_MODE))) {
        ChunkingMode.LINE_BASED -> DefaultChunkingStrategy()
        ChunkingMode.SEMANTIC -> SemanticChunkingStrategy()
    }

    val fileAnalyzerService: FileAnalyzerService? = if (projectRoot != null && embeddingsService != null) {
        FileAnalyzerService(
            configService = configService,
            ragRepository = ragRepository,
            chunkingStrategy = ragChunkingStrategy,
            embeddingsService = embeddingsService,
            analyzers = languageAnalyzers,
            scope = scope
        )
    } else null

    val richProjectAnalysisEngine: RichProjectAnalysisEngine? =
        if (projectRoot != null && fileAnalyzerService != null) {
            RichProjectAnalysisEngine(
                fileAnalyzerService = fileAnalyzerService,
                configService = configService,
                repository = analysisReportRepository,
                languageAnalyzers = languageAnalyzers
            )
        } else null

    val projectAnalyzer: ProjectAnalyzerService? = if (projectRoot != null) {
        ProjectAnalyzerService(configService, richProjectAnalysisEngine)
    } else null

    val contextService: ContextService? = if (projectRoot != null && projectAnalyzer != null) {
        ContextService(
            projectAnalyzer = projectAnalyzer,
            taskRepository = taskRepository,
            chatMessageRepository = chatMessageRepository,
            subtaskRepository = subtaskRepository,
            fileAnalyzerService = fileAnalyzerService,
            configService = configService,
            workingMemoryService = workingMemoryService,
            conversationSummaryService = conversationSummaryService,
            platformProject = platformProject,
        )
    } else null

    val snapshotService: SnapshotService? = if (projectRoot != null) {
        SnapshotService(snapshotRepository, snapshotGroupRepository, projectRoot)
    } else null

    /**
     * Lazy `rag_search` backend — resolves the configured embedding provider on first
     * access and returns null (with a WARN) if that fails.
     */
    val ragSearchService: RagSearchService? by lazy {
        try {
            val (providerId, _) = embeddingProviderFactory.resolve(configService.getEmbeddingModel())
            RagSearchService(ragRepository, embeddingProviderFactory.forProvider(providerId))
        } catch (e: Exception) {
            logger.warn(e) { "Failed to initialize RagSearchService: ${e.message}" }
            null
        }
    }
}
