package pl.jclab.refio.core.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import java.util.UUID

/**
 * Chat messages table definition using Exposed ORM DSL
 * Stores conversation history for tasks
 *
 * Extended with tool call support for turn-loop pattern
 */
object ChatMessagesTable : Table("chat_messages") {
    val id = varchar("id", 36).clientDefault { UUID.randomUUID().toString() }
    val taskId = varchar("task_id", 36).references(TasksTable.id, onDelete = ReferenceOption.CASCADE)
    val agentInstanceId = varchar("agent_instance_id", 36).nullable()  // Links message to specific agent in multi-agent sessions
    val role = enumerationByName<MessageRole>("role", 16)
    val content = text("content")
    val thinking = text("thinking").nullable()  // Reasoning process from models (gpt-oss, Claude)
    val metadata = text("metadata").nullable()  // JSON field for additional data

    // Tool call support for turn-loop pattern
    val toolCallsJson = text("tool_calls_json").nullable()  // JSON array of ToolCallData
    val toolCallId = varchar("tool_call_id", 255).nullable()  // For TOOL role - references the tool call this is a result for

    // Tool result summarization
    val isSummarized = bool("is_summarized").default(false)  // Whether content is a summary of the original output
    val rawOutput = text("raw_output").nullable()  // Full original output (for UI or last tool)

    // Metrics (Bug #4 fix)
    val tokensIn = integer("tokens_in").nullable()
    val tokensOut = integer("tokens_out").nullable()
    val cost = double("cost").nullable()

    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val seq = long("seq").clientDefault { System.nanoTime() }

    override val primaryKey = PrimaryKey(id)

    init {
        // Index for efficient retrieval of messages by task
        index("idx_chat_messages_task_created", false, taskId, createdAt)
    }
}

/**
 * Tool call data for ASSISTANT messages.
 * Represents a single tool invocation requested by the LLM.
 *
 * Part of turn-loop pattern
 */
@Serializable
data class ToolCallData(
    val id: String,
    val name: String,
    val arguments: String,  // JSON string of tool arguments
    val error: String? = null
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJsonList(jsonString: String?): List<ToolCallData>? {
            if (jsonString.isNullOrBlank()) return null
            return try {
                json.decodeFromString<List<ToolCallData>>(jsonString)
            } catch (e: Exception) {
                null
            }
        }

        fun toJsonList(toolCalls: List<ToolCallData>?): String? {
            if (toolCalls.isNullOrEmpty()) return null
            return json.encodeToString(toolCalls)
        }
    }
}

/**
 * Chat message data class for results
 *
 * Extended with toolCalls and toolCallId for turn-loop pattern
 * Extended with isSummarized and rawOutput for tool result summarization
 * Extended with thinking for reasoning models (gpt-oss, Claude)
 */
data class ChatMessage(
    val id: String,
    val taskId: String,
    val agentInstanceId: String? = null,
    val role: MessageRole,
    val content: String,
    val thinking: String? = null,         // Reasoning process (gpt-oss, Claude)
    val metadata: String?,
    val toolCalls: List<ToolCallData>?,  // For ASSISTANT - tool calls made
    val toolCallId: String?,              // For TOOL - which tool call this is a result for
    val isSummarized: Boolean = false,    // Whether content is a summary
    val rawOutput: String? = null,        // Full original output
    val tokensIn: Int?,
    val tokensOut: Int?,
    val cost: Double?,
    val createdAt: Long
)
