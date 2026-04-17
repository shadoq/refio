package pl.jclab.refio.core.api

import pl.jclab.refio.core.db.ApprovalStatus
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.llm.ModelConfig
import pl.jclab.refio.core.models.context.CurrentTaskDTO
import pl.jclab.refio.core.models.context.SubtaskDTO
import pl.jclab.refio.core.models.context.ExecutedStepDTO
import pl.jclab.refio.core.models.context.ConversationMessageDTO
import pl.jclab.refio.api.models.ContextReference

/**
 * API Models for CoreApiRouter and domain routers.
 *
 * These models define request/response DTOs for the API layer.
 */

// ========== Constants ==========

const val LEGACY_PROJECT_ID = "legacy_unknown"
const val LEGACY_PROJECT_PATH = "unknown"

// ========== Tasks API ==========

data class CreateTaskRequest(
    val name: String,
    val mode: TaskMode,
    val projectId: String = LEGACY_PROJECT_ID,
    val projectPath: String = LEGACY_PROJECT_PATH,
    val readOnly: Boolean? = null,
    val requiresPlanApproval: Boolean? = null
)

data class UpdateTaskRequest(
    val name: String? = null,
    val mode: TaskMode? = null,
    val status: TaskStatus? = null,
    val readOnly: Boolean? = null,
    val pinned: Boolean? = null,
    val executionMode: ExecutionMode? = null,
    val requiresPlanApproval: Boolean? = null,
    val planApproved: Boolean? = null,
    val uiState: String? = null,
    val rate: Int? = null  // User rating: 1 (positive) or -1 (negative), null if not rated
)

// ========== Turn API ==========

enum class TurnRunProfile {
    DEFAULT,
    SUBAGENT
}

data class TurnProfileOverrides(
    val subagentName: String? = null,
    val systemPromptOverride: String? = null,
    val allowedTools: List<String>? = null,
    val disallowedTools: List<String>? = null,
    val modelOverride: String? = null,
    val providerOverride: String? = null,
    val maxIterationsOverride: Int? = null,
    val parentRunId: String? = null,
    val depth: Int = 0,
    val subagentChain: List<String> = emptyList(),
    val contextProfile: pl.jclab.refio.core.subagents.models.SubagentContextProfile? = null,
    /**
     * Reasoning effort override for reasoning-capable models. Values: "low" | "medium" | "high".
     * Sourced from SubagentDefinition.reasoningEffort. Null = use global GENERAL_THINKING_ENABLED config.
     */
    val reasoningEffort: String? = null
)

/**
 * Request for executing a single turn in the turn-loop pattern.
 *
 * This replaces the plan-based execution with Codex CLI-style turn loop.
 * A turn consists of:
 * 1. User input
 * 2. Model processes and may emit tool calls
 * 3. Tools are executed, results added to context
 * 4. Model continues until text response (no more tool calls)
 * 5. Turn completes
 *
 * @property taskId Task/session ID
 * @property userInput User's input message for this turn
 * @property mode Task mode (CHAT, PLAN, or AGENT)
 * @property executionMode Execution mode (AUTO or INTERACTIVE)
 * @property model Optional model override
 * @property provider Optional provider override
 */
data class TurnRequest(
    val taskId: String,
    val userInput: String,
    val mode: TaskMode,
    val executionMode: ExecutionMode = ExecutionMode.AUTO,
    val model: String? = null,
    val provider: String? = null,
    val userContextRefs: List<ContextReference> = emptyList(),
    val runProfile: TurnRunProfile = TurnRunProfile.DEFAULT,
    val profileOverrides: TurnProfileOverrides? = null,
    /**
     * Optional override for the sessionId used when emitting AgentEventBus events.
     * Used by MultiAgentRouter so that Turn/LLM/Tool events from a sub-agent are
     * attributed to the parent multi-agent session (not the sub-task).
     * Defaults to [taskId] when null.
     */
    val emitSessionId: String? = null,
    /**
     * Optional override for the sourceAgentId used when emitting AgentEventBus events.
     * Used by MultiAgentRouter to attribute per-turn events to a specific sub-agent.
     * Defaults to [taskId] when null.
     */
    val emitSourceAgentId: String? = null
)

data class ToolDefinitionInfo(
    val name: String,
    val description: String,
    val mode: String,
    val category: String,
    val defaultPlanMode: String,
    val defaultAgentMode: String
)

// ========== Messages API ==========

data class MessageResponse(
    val id: String,
    val taskId: String,
    val role: String,
    val content: String,
    val thinking: String? = null,      // Reasoning process (gpt-oss, Claude)
    val metadata: String?,
    val toolCallsJson: String?,  // JSON array of ToolCallData for ASSISTANT messages
    val toolCallId: String?,     // For TOOL messages - references which tool call this is a result for
    val tokensIn: Int?,
    val tokensOut: Int?,
    val cost: Double?,
    val createdAt: Long,
    val isSummarized: Boolean = false,  // For TOOL messages - whether content is a summary
    val rawOutput: String? = null,      // For TOOL messages - original full output before summarization
    val agentName: String? = null,      // Subagent name for multi-agent UI
    val agentDepth: Int? = null         // Nesting depth (0=main, 1=subagent)
)

data class GetMessagesResponse(
    val messages: List<MessageResponse>,
    val count: Int
)

// ========== Configuration API ==========

data class GetModelsResponse(
    val models: List<ModelConfig>,
    val count: Int
)

data class GetDefaultModelResponse(
    val operation: String,
    val modelId: String,
    val provider: String
)

data class SetDefaultModelRequest(
    val operation: ModelOperation,
    val modelId: String,
    val provider: String
)

data class SetDefaultModelResponse(
    val operation: String,
    val modelId: String,
    val provider: String,
    val scope: String
)

data class SetDefaultModelAllModesRequest(
    val modelId: String,
    val provider: String
)

data class SetDefaultModelAllModesResponse(
    val modelId: String,
    val provider: String,
    val scope: String,
    val modes: List<String>
)

data class ModelInfo(
    val id: String,
    val provider: String,
    val name: String,
    val contextSize: Int,
    val capabilities: List<String>,
    val pricing: ModelPricing?,
    val showInDropdown: Boolean = true
)

data class ModelPricing(
    val inputPer1MTokens: Double,
    val outputPer1MTokens: Double
)

// ========== Step Planning & Execution API ==========

data class PlanStepResponse(
    val tools: List<ToolCallResponse>,
    val description: String,
    val estimatedDurationMs: Int,
    val dependencies: List<String>
)

data class ToolCallResponse(
    val name: String,
    val params: Map<String, Any>,
    val expectedOutput: String?
)

data class ExecuteStepResponse(
    val status: String,
    val summary: String,
    val durationMs: Int,
    val error: String?
)

data class AutoExecutionResponse(
    val totalSteps: Int,
    val completedSteps: Int,
    val failedSteps: Int,
    val durationMs: Int,
    val success: Boolean,
    val error: String? = null
)

data class PlanSummaryResponse(
    val taskId: String,
    val totalSteps: Int,
    val readOnlySteps: Int,
    val writeSteps: Int,
    val requiresApproval: Boolean,
    val isApproved: Boolean,
    val steps: List<PlanStepSummaryResponse>
)

data class PlanStepSummaryResponse(
    val id: String,
    val description: String,
    val tool: String,
    val status: String,
    val isWrite: Boolean
)

data class OrchestrationExecutionResponse(
    val success: Boolean,
    val stepsExecuted: Int,
    val stepsFailed: Int,
    val reflectionsCount: Int,
    val planModificationsCount: Int,
    val userQuestionsCount: Int,
    val durationMs: Int,
    val error: String? = null
)

// ========== RAG API ==========

data class RagSearchResultDto(
    val chunkId: Int,
    val fileId: Int,
    val filePath: String,
    val content: String,
    val startLine: Int?,
    val endLine: Int?,
    val similarity: Float,
    val contentType: String
)

// ========== Tasks API (extended) ==========

data class TaskResponse(
    val id: String,
    val name: String,
    val mode: String,
    val status: String,
    val readOnly: Boolean,
    val pinned: Boolean,
    val executionMode: String,
    val requiresPlanApproval: Boolean = false,
    val planApproved: Boolean = false,
    val uiState: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val costUsd: Double = 0.0,
    val rate: Int? = null,  // User rating: 1 (positive) or -1 (negative), null if not rated
    val projectId: String = LEGACY_PROJECT_ID,
    val projectPath: String = LEGACY_PROJECT_PATH
)

data class ListTasksResponse(
    val tasks: List<TaskResponse>,
    val count: Int
)

data class HealthResponse(
    val status: String,
    val version: String,
    val timestamp: Long,
    val message: String
)

// ========== Subtasks API (extended) ==========

data class GetSubtasksResponse(
    val subtasks: List<SubtaskResponse>,
    val count: Int
)

data class UpdateSubtaskRequest(
    val status: pl.jclab.refio.core.db.TaskStatus? = null,
    val approvalStatus: pl.jclab.refio.core.db.ApprovalStatus? = null
)

data class DeleteSubtasksResponse(
    val deletedCount: Int,
    val message: String
)

// ========== Step Execution API (extended) ==========

/**
 * Response for single step execution with orchestration (INTERACTIVE mode)
 */
data class SingleStepOrchestrationResponse(
    val status: String,
    val summary: String,
    val durationMs: Int,
    val error: String?,
    val reflectionDecision: String?,           // CONTINUE, MODIFY_PLAN, ASK_USER, ABORT
    val reflectionConfidence: String?,         // HIGH, MEDIUM, LOW
    val reflectionReasoning: String?,
    val userQuestion: String?,
    val planModified: Boolean
)

// ========== Snapshot API ==========

data class SnapshotResponse(
    val snapshotId: String,
    val files: Map<String, String>  // file path -> content
)

data class SnapshotSummary(
    val snapshotId: String,
    val taskId: String,
    val subtaskName: String,
    val filesCount: Int,
    val createdAt: Long
)

// ========== Configuration Management API ==========

data class UpdateConfigResponse(
    val section: String,
    val scope: String,
    val updatedKeys: List<String>,
    val success: Boolean
)

data class ResetConfigResponse(
    val success: Boolean,
    val message: String,
    val affectedSections: List<String>
)

data class GetConfigResponse(
    val section: String,
    val scope: String,
    val settings: Map<String, Any>
)

// ========== Provider Testing API ==========

data class TestConnectionResult(
    val success: Boolean,
    val latencyMs: Int,
    val message: String,
    val details: Map<String, Any>?
)

// ========== Project Context API ==========

data class ProjectContextResponse(
    val projectPath: String,
    val projectType: String,
    val technologies: List<String>,
    val technologyVersions: Map<String, String?> = emptyMap(),
    val infrastructure: List<String> = emptyList(),  // Infrastructure tools (Docker, K8s, CI/CD) - ADR 0040
    val primaryLanguage: String = "Unknown",  // Primary programming language detected - ADR 0040
    val mainLanguage: String,
    val complexity: String,
    val totalFiles: Int,
    val fileTypes: Map<String, Int>,
    val keyComponents: List<String>,
    val dependencies: Map<String, Any>,
    val codeAnalysis: Map<String, Any>,
    val currentTask: CurrentTaskDTO?,
    val subtasks: List<SubtaskDTO>,
    val executedSteps: List<ExecutedStepDTO> = emptyList(),
    val completedFiles: List<String>,
    val llmContextPrompt: String?,
    val analyzedAt: Long,
    val contextBuiltAt: Long,
    // User requirements extracted from task description
    val userRequirements: Map<String, Any> = emptyMap(),
    val mcpResources: List<MCPResourceResponse> = emptyList(),
    // User context references from @mentions (files, selections, providers)
    val userContextRefs: List<UserContextRefDTO> = emptyList(),
    // Conversation history
    val conversationHistory: List<ConversationMessageDTO> = emptyList(),
    // Domain analysis scores
    val domainAnalysis: Map<String, Any> = emptyMap(),
    // Project structure details
    val directoryCount: Int = 0,
    val maxDepth: Int = 0,
    // Token usage per context section (for visualization)
    val contextSectionTokens: Map<String, ContextSectionTokenInfo> = emptyMap(),
    // Total estimated tokens
    val totalEstimatedTokens: Int = 0,
    // Active LLM request (single call) estimated tokens
    val activeEstimatedTokens: Int = 0,
    // Auxiliary prompts (tools/summaries/templates) estimated tokens
    val auxiliaryEstimatedTokens: Int = 0,
    // Combined preview (active + auxiliary) estimated tokens
    val combinedEstimatedTokens: Int = 0,
    val semanticSummary: String? = null,
    val projectInstructions: String? = null,
    // Actual TASK_REQUIREMENTS section content that will be sent to LLM
    val taskRequirementsPrompt: String? = null,
    // Actual RECENT_WORK section content that will be sent to LLM (after compression)
    val recentWorkPrompt: String? = null,
    // Active LLM request preview (exact runtime shape for next call)
    val activeLlmRequestPrompt: String? = null,
    // Auxiliary prompt preview (tool/system/user templates not in active request)
    val auxiliaryPromptsPreview: String? = null
)

/**
 * Token usage info for a single context section
 */
data class ContextSectionTokenInfo(
    val name: String,
    val tokens: Int,
    val chars: Int,
    val percentage: Double  // Percentage of total context
)

/**
 * User context reference from @mentions
 */
data class UserContextRefDTO(
    val type: String,           // PROVIDER, FILE, FOLDER, SELECTION, etc.
    val providerId: String?,    // Provider ID (e.g., "file", "grep", "open")
    val path: String?,          // File path or query
    val displayName: String,    // Human-readable name
    val content: String,        // Resolved content (truncated for display)
    val sizeBytes: Long = 0,
    val estimatedTokens: Int = 0
)

data class MCPResourceResponse(
    val serverId: String,
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null
)

// ========== Multi-Agent API ==========

/**
 * Request to launch a multi-agent session.
 *
 * @property name Session name
 * @property yamlDefinition YAML content defining agents, dependencies, and tasks
 * @property model Default model override for all agents (individual agents can override)
 * @property provider Default provider override
 */
data class MultiAgentSessionRequest(
    val name: String,
    val yamlDefinition: String,
    val model: String? = null,
    val provider: String? = null
)

/**
 * Response from launching or querying a multi-agent session.
 */
data class MultiAgentSessionResponse(
    val sessionId: String,
    val name: String,
    val status: String,
    val agents: List<MultiAgentInstanceResponse>,
    val totalTokens: Long = 0,
    val totalCostUsd: Double = 0.0,
    val durationMs: Long = 0,
    val createdAt: Long,
    val completedAt: Long? = null
)

data class MultiAgentInstanceResponse(
    val agentName: String,
    val status: String,
    val success: Boolean? = null,
    val response: String? = null,
    val tokensUsed: Long = 0,
    val costUsd: Double = 0.0,
    val durationMs: Long = 0,
    val error: String? = null
)
