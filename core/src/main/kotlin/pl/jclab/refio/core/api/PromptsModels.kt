package pl.jclab.refio.core.api

import pl.jclab.refio.core.db.PromptType

/**
 * Prompts API Request/Response models
 */

/**
 * Prompt data model for API responses
 */
data class PromptDto(
    val id: String,
    val name: String,
    val type: String,  // PromptType enum as string
    val content: String,
    val description: String?,
    val isCustom: Boolean,
    val isEnabled: Boolean,
    val orderIndex: Int,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Request to create or update a rule
 */
data class SaveRuleRequest(
    val id: String? = null,
    val name: String,
    val content: String,
    val description: String? = null,
    val isEnabled: Boolean = true
)

/**
 * Request to create or update a slash command
 */
data class SaveCommandRequest(
    val id: String? = null,
    val name: String,  // e.g., "/refactor" or "refactor" (will be normalized)
    val content: String,
    val description: String? = null,
    val isEnabled: Boolean = true
)

/**
 * Request to update system prompt
 */
data class UpdateSystemPromptRequest(
    val type: PromptType,  // SYSTEM_CHAT, SYSTEM_PLAN, or SYSTEM_AGENT
    val content: String
)

/**
 * Request to get system prompt with variables
 */
data class GetSystemPromptRequest(
    val type: PromptType,
    val variables: Map<String, String> = emptyMap()
)

/**
 * Response containing system prompt content
 */
data class SystemPromptResponse(
    val type: String,
    val content: String
)

/**
 * Response containing list of prompts
 */
data class PromptsListResponse(
    val prompts: List<PromptDto>,
    val count: Int
)

/**
 * Response for single prompt
 */
data class PromptResponse(
    val prompt: PromptDto
)

/**
 * Response for delete operation
 */
data class DeletePromptResponse(
    val success: Boolean,
    val id: String
)
