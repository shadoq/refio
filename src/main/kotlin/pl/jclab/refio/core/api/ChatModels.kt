package pl.jclab.refio.core.api

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.db.TaskMode

/**
 * Chat API Request/Response models
 *
 * Based on Python implementation in agent/core/api/v1/chat.py
 */

/**
 * LLM parameters for chat request
 */
data class LLMParams(
    val model: String? = null,
    val provider: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null
)

/**
 * Chat request model
 *
 * @param taskId Existing task UUID (must be created first via POST /v1/tasks)
 * @param mode Task mode (must be CHAT)
 * @param input User message content
 * @param contextRefs User-provided context references from @ mentions (resolved by ContextService)
 * @param params Model parameters (model, provider, temperature, etc.)
 */
data class ChatRequest(
    val taskId: String,
    val mode: TaskMode,
    val input: String,
    val contextRefs: List<ContextReference> = emptyList(),
    val params: LLMParams = LLMParams()
)

/**
 * Token/cost statistics
 */
data class ChatCosts(
    val tokensIn: Int,
    val tokensOut: Int,
    val usdEst: Double
)

/**
 * Chat response model
 *
 * @param schemaVersion API schema version
 * @param requestId Unique request identifier
 * @param taskId Task UUID
 * @param messageId Assistant message UUID
 * @param output Assistant response content
 * @param costs Token usage and cost statistics
 * @param toolCalls List of tool calls (empty for CHAT mode)
 * @param diffSummary File diff summary (null for CHAT mode)
 * @param errorCode Error code if request failed (null on success)
 */
data class ChatResponse(
    val schemaVersion: String = "1.0",
    val requestId: String,
    val taskId: String,
    val messageId: String,
    val output: String,
    val costs: ChatCosts,
    val toolCalls: List<Any> = emptyList(),  // No tools in CHAT mode
    val diffSummary: Map<String, Any>? = null,  // No file changes in CHAT mode
    val errorCode: String? = null
)
