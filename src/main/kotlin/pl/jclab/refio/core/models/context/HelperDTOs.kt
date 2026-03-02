package pl.jclab.refio.core.models.context

import java.time.Instant

/**
 * Helper DTOs for ProjectContext
 * Based on Python context_dto.py
 */

data class FileInfoDTO(
    val path: String,
    val name: String,
    val size: Long = 0,
    val type: String = "",
    val extension: String? = null,
    val content: String? = null
)

data class CurrentTaskDTO(
    val id: String,
    val name: String,
    val description: String,
    val status: String,
    val priority: String? = null,
    val executionMode: String? = null,
    val context: Map<String, Any> = emptyMap()
)

data class SubtaskDTO(
    val id: String,
    val name: String,
    val description: String,
    val status: String,
    val order: Int,
    val agentType: String? = null,
    val stepType: String? = null,
    val requiresConfirmation: Boolean = true,
    val expectedOutcome: String? = null,
    val tool: String? = null,
    val toolArgs: Map<String, Any> = emptyMap()
)

data class ConversationMessageDTO(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Instant? = null,
    val processingTime: Double? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cost: Double? = null,
    val modelId: String? = null,
    val metadata: Map<String, Any?>? = null
)

data class AgentInfoDTO(
    val type: String,
    val capabilities: List<String> = emptyList(),
    val tools: List<String> = emptyList()
)

data class SubtaskContextDTO(
    val id: String? = null,
    val name: String,
    val description: String,
    val agentType: String? = null,
    val stepType: String? = null,
    val tool: String? = null,
    val toolArgs: Map<String, Any> = emptyMap(),
    val order: Int? = null
)

data class TaskContextDTO(
    val id: String,
    val name: String,
    val description: String,
    val status: String,
    val priority: String? = null
)

/**
 * Code fragment DTO - relevant code/documentation fragment from RAG search
 */
data class CodeFragmentDTO(
    val filePath: String,
    val content: String,
    val startLine: Int?,
    val endLine: Int?,
    val similarity: Float,
    val contentType: String  // PROJECT_CODE or DOCUMENTATION
)

/**
 * Resolved context reference DTO - user-provided context from @ mentions
 * Contains resolved content ready for LLM consumption
 */
data class ResolvedContextDTO(
    val type: String,           // PROVIDER, FILE, FOLDER, SELECTION, etc.
    val providerId: String?,    // Provider ID (e.g., "file", "grep", "open")
    val path: String?,          // File path or query
    val displayName: String,    // Human-readable name
    val content: String,        // Resolved content
    val sizeBytes: Long = 0,
    val estimatedTokens: Int = 0
)

data class AgentConfigDTO(
    val name: String,
    val role: String? = null,
    val tools: List<String> = emptyList(),
    val metadata: Map<String, Any> = emptyMap()
)

data class ToolMetadataDTO(
    val name: String,
    val description: String? = null,
    val params: Map<String, Any> = emptyMap()
)
