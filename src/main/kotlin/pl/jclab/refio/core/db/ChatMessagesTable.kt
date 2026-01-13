package pl.jclab.refio.core.db

import org.jetbrains.exposed.sql.*
import java.util.UUID

/**
 * Chat messages table definition using Exposed ORM DSL
 * Stores conversation history for tasks
 */
object ChatMessagesTable : Table("chat_messages") {
    val id = varchar("id", 36).clientDefault { UUID.randomUUID().toString() }
    val taskId = varchar("task_id", 36).references(TasksTable.id, onDelete = ReferenceOption.CASCADE)
    val role = enumerationByName<MessageRole>("role", 16)
    val content = text("content")
    val metadata = text("metadata").nullable()  // JSON field for additional data

    // Metrics (Bug #4 fix)
    val tokensIn = integer("tokens_in").nullable()
    val tokensOut = integer("tokens_out").nullable()
    val cost = double("cost").nullable()

    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }

    override val primaryKey = PrimaryKey(id)

    init {
        // Index for efficient retrieval of messages by task
        index("idx_chat_messages_task_created", false, taskId, createdAt)
    }
}

/**
 * Chat message data class for results
 */
data class ChatMessage(
    val id: String,
    val taskId: String,
    val role: MessageRole,
    val content: String,
    val metadata: String?,
    val tokensIn: Int?,
    val tokensOut: Int?,
    val cost: Double?,
    val createdAt: Long
)
