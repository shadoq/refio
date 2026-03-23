package pl.jclab.refio.core.db.repositories

import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.logging.dualLogger
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = dualLogger("ChatMessageRepository")

/**
 * Repository for ChatMessage database operations
 * Manages conversation history for tasks
 */
class ChatMessageRepository {

    /**
     * Create a new chat message
     */
    fun create(
        taskId: String,
        role: MessageRole,
        content: String,
        thinking: String? = null,
        metadata: String? = null,
        toolCalls: List<ToolCallData>? = null,
        toolCallId: String? = null,
        isSummarized: Boolean = false,
        rawOutput: String? = null,
        tokensIn: Int? = null,
        tokensOut: Int? = null,
        cost: Double? = null
    ): ChatMessage {
        return transaction {
            val messageId = ChatMessagesTable.insert {
                it[ChatMessagesTable.taskId] = taskId
                it[ChatMessagesTable.role] = role
                it[ChatMessagesTable.content] = content
                it[ChatMessagesTable.thinking] = thinking
                it[ChatMessagesTable.metadata] = metadata
                it[ChatMessagesTable.toolCallsJson] = ToolCallData.toJsonList(toolCalls)
                it[ChatMessagesTable.toolCallId] = toolCallId
                it[ChatMessagesTable.isSummarized] = isSummarized
                it[ChatMessagesTable.rawOutput] = rawOutput
                it[ChatMessagesTable.tokensIn] = tokensIn
                it[ChatMessagesTable.tokensOut] = tokensOut
                it[ChatMessagesTable.cost] = cost
            } get ChatMessagesTable.id

            val toolCallsInfo = if (toolCalls != null) ", toolCalls=${toolCalls.size}" else ""
            val toolCallIdInfo = if (toolCallId != null) ", toolCallId=$toolCallId" else ""
            val thinkingInfo = if (!thinking.isNullOrEmpty()) ", thinking=${thinking.length} chars" else ""
            logger.info { "Created chat message: id=$messageId, taskId=$taskId, role=$role$toolCallsInfo$toolCallIdInfo$thinkingInfo, tokens=$tokensIn/$tokensOut, cost=$cost" }

            findById(messageId) ?: throw IllegalStateException("Failed to retrieve created message")
        }
    }

    /**
     * Create a TOOL message with the result of a tool call.
     */
    fun createToolResult(
        taskId: String,
        toolCallId: String,
        result: String,
        isSummarized: Boolean = false,
        rawOutput: String? = null,
        metadata: String? = null
    ): ChatMessage {
        return create(
            taskId = taskId,
            role = MessageRole.TOOL,
            content = result,
            metadata = metadata,
            toolCallId = toolCallId,
            isSummarized = isSummarized,
            rawOutput = rawOutput
        )
    }

    /**
     * Find message by ID
     */
    fun findById(id: String): ChatMessage? {
        return transaction {
            ChatMessagesTable.selectAll()
                .where { ChatMessagesTable.id eq id }
                .map { rowToChatMessage(it) }
                .singleOrNull()
        }
    }

    /**
     * Find all messages for a task, ordered by creation time
     */
    fun findByTaskId(taskId: String): List<ChatMessage> {
        return transaction {
            ChatMessagesTable.selectAll()
                .where { ChatMessagesTable.taskId eq taskId }
                .orderBy(ChatMessagesTable.createdAt to SortOrder.ASC, ChatMessagesTable.id to SortOrder.ASC)
                .map { rowToChatMessage(it) }
        }
    }

    /**
     * Find messages by role
     */
    fun findByRole(taskId: String, role: MessageRole): List<ChatMessage> {
        return transaction {
            ChatMessagesTable.selectAll()
                .where { (ChatMessagesTable.taskId eq taskId) and (ChatMessagesTable.role eq role) }
                .orderBy(ChatMessagesTable.createdAt to SortOrder.ASC, ChatMessagesTable.id to SortOrder.ASC)
                .map { rowToChatMessage(it) }
        }
    }

    /**
     * Delete message by ID
     */
    fun delete(id: String): Boolean {
        return transaction {
            val deleted = ChatMessagesTable.deleteWhere { ChatMessagesTable.id eq id }
            deleted > 0
        }
    }

    /**
     * Update message metadata.
     */
    fun updateMetadata(id: String, metadata: String?): ChatMessage? {
        return transaction {
            ChatMessagesTable.update({ ChatMessagesTable.id eq id }) {
                it[ChatMessagesTable.metadata] = metadata
            }
            findById(id)
        }
    }

    /**
     * Update message content and metadata.
     */
    fun updateContentAndMetadata(id: String, content: String, metadata: String?): ChatMessage? {
        return transaction {
            ChatMessagesTable.update({ ChatMessagesTable.id eq id }) {
                it[ChatMessagesTable.content] = content
                it[ChatMessagesTable.metadata] = metadata
            }
            findById(id)
        }
    }

    /**
     * Delete all messages for a task
     */
    fun deleteByTaskId(taskId: String): Int {
        return transaction {
            val deleted = ChatMessagesTable.deleteWhere { ChatMessagesTable.taskId eq taskId }
            logger.info { "Deleted $deleted messages for task: taskId=$taskId" }
            deleted
        }
    }

    /**
     * Delete conversation history starting from the given message (inclusive).
     *
     * Ordering is consistent with [findByTaskId]: createdAt ASC, id ASC.
     * If multiple messages share the same createdAt, the id tie-breaker defines what is "after".
     *
     * @throws IllegalArgumentException If message is not found or does not belong to task
     */
    fun deleteFromMessageInclusive(taskId: String, fromMessageId: String): Int {
        return transaction {
            val pivot = ChatMessagesTable
                .selectAll()
                .where { (ChatMessagesTable.id eq fromMessageId) and (ChatMessagesTable.taskId eq taskId) }
                .singleOrNull()
                ?: throw IllegalArgumentException("Message not found in task: taskId=$taskId, messageId=$fromMessageId")

            val pivotCreatedAt = pivot[ChatMessagesTable.createdAt]
            val pivotId = pivot[ChatMessagesTable.id]

            val deleted = ChatMessagesTable.deleteWhere {
                (ChatMessagesTable.taskId eq taskId) and (
                    (ChatMessagesTable.createdAt greater pivotCreatedAt) or (
                        (ChatMessagesTable.createdAt eq pivotCreatedAt) and (ChatMessagesTable.id greaterEq pivotId)
                        )
                    )
            }

            logger.info {
                "Deleted $deleted messages from pivot (inclusive): taskId=$taskId, fromMessageId=$fromMessageId"
            }

            deleted
        }
    }

    /**
     * Count messages for a task
     */
    fun countByTaskId(taskId: String): Long {
        return transaction {
            ChatMessagesTable.selectAll()
                .where { ChatMessagesTable.taskId eq taskId }
                .count()
        }
    }

    /**
     * Map database row to ChatMessage data class
     */
    private fun rowToChatMessage(row: ResultRow): ChatMessage {
        return ChatMessage(
            id = row[ChatMessagesTable.id],
            taskId = row[ChatMessagesTable.taskId],
            role = row[ChatMessagesTable.role],
            content = row[ChatMessagesTable.content],
            thinking = row[ChatMessagesTable.thinking],
            metadata = row[ChatMessagesTable.metadata],
            toolCalls = ToolCallData.fromJsonList(row[ChatMessagesTable.toolCallsJson]),
            toolCallId = row[ChatMessagesTable.toolCallId],
            isSummarized = row[ChatMessagesTable.isSummarized],
            rawOutput = row[ChatMessagesTable.rawOutput],
            tokensIn = row[ChatMessagesTable.tokensIn],
            tokensOut = row[ChatMessagesTable.tokensOut],
            cost = row[ChatMessagesTable.cost],
            createdAt = row[ChatMessagesTable.createdAt]
        )
    }
}
