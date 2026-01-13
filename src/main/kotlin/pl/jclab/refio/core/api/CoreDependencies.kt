package pl.jclab.refio.core.api

import pl.jclab.refio.core.db.repositories.*
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.services.*
import pl.jclab.refio.core.services.analysis.EmbeddingsService
import pl.jclab.refio.core.services.analysis.FileAnalyzerService
import pl.jclab.refio.core.tools.base.ToolRegistry

/**
 * Dependency container for CoreApiRouter and domain routers.
 * Makes dependencies explicit and testable.
 *
 * This container groups all services, repositories, and utilities needed by routers,
 * allowing easy mocking and testing of individual routers.
 *
 * @property taskRepository Task management repository
 * @property subtaskRepository Subtask management repository
 * @property chatMessageRepository Chat message storage
 * @property snapshotRepository File snapshot storage for rollback
 * @property apiLogRepository LLM API call logging
 * @property configRepository Configuration storage
 * @property promptsRepository System prompts storage
 * @property ragRepository RAG indexing data
 * @property documentationRepository External documentation sources
 * @property projectAnalysisReportRepository Project analysis reports
 *
 * @property chatService Chat conversation management
 * @property planningService Plan generation and validation
 * @property agentExecutor Agent execution orchestration
 * @property stepPlanner Individual step planning
 * @property stepSummarizer Step execution summarization
 * @property toolExecutor Tool execution with permissions
 * @property toolPermissionsService Tool permission management
 * @property ragSearchService RAG similarity search (nullable - requires projectRoot)
 * @property embeddingsService Embedding generation (nullable - requires projectRoot)
 * @property fileAnalyzerService File analysis and chunking (nullable - requires projectRoot)
 * @property configService Configuration management
 * @property contextService Project context building (nullable - requires projectRoot)
 * @property snapshotService File snapshot management (nullable - requires projectRoot)
 * @property projectAnalyzer Project analysis service (nullable - requires projectRoot)
 * @property promptsService System prompts management
 *
 * @property toolRegistry Tool catalog
 * @property llmClient LLM provider client
 *
 * @property projectRoot Project root path (nullable - not available for non-project routers)
 * @property ideProject IntelliJ project instance (nullable - not available for CLI mode)
 */
data class CoreDependencies(
    // Repositories
    val taskRepository: TaskRepository,
    val subtaskRepository: SubtaskRepository,
    val chatMessageRepository: ChatMessageRepository,
    val snapshotRepository: SnapshotRepository,
    val apiLogRepository: ApiLogRepository,
    val configRepository: ConfigRepository,
    val promptsRepository: PromptsRepository,
    val ragRepository: RagRepository,
    val documentationRepository: DocumentationRepository,
    val projectAnalysisReportRepository: ProjectAnalysisReportRepository,

    // Services
    val chatService: ChatService,
    val planningService: PlanningService,
    val agentExecutor: AgentExecutor?,
    val stepPlanner: StepPlanner?,
    val stepSummarizer: StepSummarizer,
    val toolExecutor: ToolExecutor?,
    val toolPermissionsService: ToolPermissionsService,
    val ragSearchService: RagSearchService?,
    val embeddingsService: EmbeddingsService?,
    val fileAnalyzerService: FileAnalyzerService?,
    val configService: ConfigService,
    val contextService: ContextService?,
    val snapshotService: SnapshotService?,
    val projectAnalyzer: ProjectAnalyzerService?,
    val promptsService: PromptsService,

    // Tools
    val toolRegistry: ToolRegistry?,

    // LLM
    val llmClient: LLMClient,

    // Project context (nullable - not available for non-project routers)
    val projectRoot: java.nio.file.Path?,
    val ideProject: com.intellij.openapi.project.Project?
)
