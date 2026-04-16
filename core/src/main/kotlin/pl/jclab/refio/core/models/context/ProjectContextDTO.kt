package pl.jclab.refio.core.models.context

import pl.jclab.refio.core.api.ContextSectionTokenInfo
import pl.jclab.refio.core.services.analysis.project.FrameworkAnalysis
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

    // Task and subtasks
    val currentTask: CurrentTaskDTO? = null,
    val subtasks: List<SubtaskDTO> = emptyList(),

    // Conversation history
    val conversationHistory: List<ConversationMessageDTO> = emptyList(),

    // Work history (from PHASE 3, refactored in ADR 0041)
    val completedFiles: List<String> = emptyList(),
    val executedSteps: List<ExecutedStepDTO> = emptyList(),

    // User requirements (extracted from task description - PHASE 2)
    val userRequirements: Map<String, Any> = emptyMap(),

    // User-provided context (from @ mentions + extracted from messages)
    val userContextRefs: List<ResolvedContextDTO> = emptyList(),

    // Project instructions (from AGENTS.md, .refio/agent.md, .refio/rules/)
    val projectInstructions: String? = null,

    // Additional context
    val mcpResources: List<MCPContextResourceDTO> = emptyList(),

    // Context generation metadata
    val contextGeneratedAt: Instant,
    val analyzerVersion: String,
    val domainAnalysis: Map<String, Double> = emptyMap(),
    val semanticSummary: String? = null,

    // Framework analysis
    val frameworkAnalysis: FrameworkAnalysis? = null,

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
