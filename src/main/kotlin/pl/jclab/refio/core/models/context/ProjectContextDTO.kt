package pl.jclab.refio.core.models.context

import pl.jclab.refio.core.api.ContextSectionTokenInfo
import java.time.Instant

data class ProjectContextDTO(
    // Core DTOs
    val metaData: MetaDataDTO,
    val summary: SummaryDTO,
    val structure: StructureDTO,
    val dependencies: DependenciesDTO,
    val codeAnalysis: CodeAnalysisDTO,
    val workspace: WorkspaceDTO,
    val executionMetadata: ExecutionMetadataDTO,

    // Project characteristics
    val projectType: String,
    val technologies: List<String> = emptyList(),
    val technologyVersions: Map<String, String?> = emptyMap(),
    val keyComponents: List<String> = emptyList(),
    val files: List<FileInfoDTO> = emptyList(),

    // Task and subtasks
    val currentTask: CurrentTaskDTO? = null,
    val subtasks: List<SubtaskDTO> = emptyList(),
    val subtaskContext: SubtaskContextDTO? = null,
    val taskContext: TaskContextDTO? = null,

    // Conversation history
    val conversationHistory: List<ConversationMessageDTO> = emptyList(),

    // Work history (from PHASE 3, refactored in ADR 0041)
    val completedFiles: List<String> = emptyList(),
    @Deprecated("Use executedSteps instead for structured history")
    val previousSubtasks: List<String> = emptyList(),
    val executedSteps: List<ExecutedStepDTO> = emptyList(),

    // User requirements (extracted from task description - PHASE 2)
    val userRequirements: Map<String, Any> = emptyMap(),

    // RAG (Retrieval-Augmented Generation) context
    val ragFragments: List<CodeFragmentDTO> = emptyList(),

    // User-provided context (from @ mentions + extracted from messages)
    val userContextRefs: List<ResolvedContextDTO> = emptyList(),

    // Multi-agent support
    val agents: List<AgentConfigDTO> = emptyList(),
    val agentInfo: List<AgentInfoDTO> = emptyList(),
    val coordinationStrategy: String? = null,
    val agentConfig: Map<String, Any> = emptyMap(),
    val toolConfig: Map<String, Any> = emptyMap(),

    // Additional context
    val availableTools: List<ToolMetadataDTO> = emptyList(),
    val templateReference: Map<String, Any> = emptyMap(),
    val mcpResources: List<MCPContextResourceDTO> = emptyList(),

    // Context generation metadata
    val contextGeneratedAt: Instant,
    val analyzerVersion: String,
    val domainAnalysis: Map<String, Double> = emptyMap(),
    val semanticMetaData: Map<String, Any> = emptyMap(),
    val workflowPatterns: Map<String, Any> = emptyMap(),
    val llmContext: Map<String, Any> = emptyMap(),
    val semanticSummary: String? = null,

    // Error information
    val sectionTokens: Map<String, ContextSectionTokenInfo>? = null,
    val error: String? = null
)

data class MCPContextResourceDTO(
    val serverId: String,
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null
)
