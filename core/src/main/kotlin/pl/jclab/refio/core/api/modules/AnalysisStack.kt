package pl.jclab.refio.core.api.modules

import kotlinx.coroutines.CoroutineScope
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.ProjectAnalysisReportRepository
import pl.jclab.refio.core.db.repositories.RagRepository
import pl.jclab.refio.core.db.repositories.SnapshotRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
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
    configService: ConfigService,
    ragRepository: RagRepository,
    snapshotRepository: SnapshotRepository,
    analysisReportRepository: ProjectAnalysisReportRepository,
    taskRepository: TaskRepository,
    chatMessageRepository: ChatMessageRepository,
    subtaskRepository: SubtaskRepository,
    workingMemoryService: WorkingMemoryService,
    conversationSummaryService: ConversationSummaryService,
    scope: CoroutineScope,
    embeddingProviderFactory: (providerId: String) -> EmbeddingProvider
) {
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
            providerFactory = embeddingProviderFactory
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
            conversationSummaryService = conversationSummaryService
        )
    } else null

    val snapshotService: SnapshotService? = if (projectRoot != null) {
        SnapshotService(snapshotRepository, projectRoot)
    } else null
}
