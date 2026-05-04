package pl.jclab.refio.core.db.repositories

import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.logging.dualLogger
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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
        subtaskId: String? = null,
        isSummarized: Boolean = false,
        rawOutput: String? = null,
        tokensIn: Int? = null,
        tokensOut: Int? = null,
        cost: Double? = null,
        agentInstanceId: String? = null,
        agentName: String? = null,
        agentDepth: Int? = null
    ): ChatMessage {
        return transaction {
            val messageId = ChatMessagesTable.insert {
                it[ChatMessagesTable.taskId] = taskId
                it[ChatMessagesTable.agentInstanceId] = agentInstanceId
                it[ChatMessagesTable.agentName] = agentName
                it[ChatMessagesTable.agentDepth] = agentDepth
                it[ChatMessagesTable.role] = role
                it[ChatMessagesTable.content] = content
                it[ChatMessagesTable.thinking] = thinking
                it[ChatMessagesTable.metadata] = metadata
                it[ChatMessagesTable.toolCallsJson] = ToolCallData.toJsonList(toolCalls)
                it[ChatMessagesTable.toolCallId] = toolCallId
                it[ChatMessagesTable.subtaskId] = subtaskId
                it[ChatMessagesTable.isSummarized] = isSummarized
                it[ChatMessagesTable.rawOutput] = rawOutput
                it[ChatMessagesTable.tokensIn] = tokensIn
                it[ChatMessagesTable.tokensOut] = tokensOut
                it[ChatMessagesTable.cost] = cost
            } get ChatMessagesTable.id

            val toolCallsInfo = if (toolCalls != null) ", toolCalls=${toolCalls.size}" else ""
            val toolCallIdInfo = if (toolCallId != null) ", toolCallId=$toolCallId" else ""
            val subtaskIdInfo = if (subtaskId != null) ", subtaskId=$subtaskId" else ""
            val thinkingInfo = if (!thinking.isNullOrEmpty()) ", thinking=${thinking.length} chars" else ""
            logger.info { "Created chat message: id=$messageId, taskId=$taskId, role=$role$toolCallsInfo$toolCallIdInfo$subtaskIdInfo$thinkingInfo, tokens=$tokensIn/$tokensOut, cost=$cost" }

            findById(messageId) ?: throw IllegalStateException("Failed to retrieve created message")
        }
    }

    /**
     * Create a TOOL message with the result of a tool call.
     */
    fun createToolResult(
        taskId: String,
        toolCallId: String,
        subtaskId: String?,
        result: String,
        isSummarized: Boolean = false,
        rawOutput: String? = null,
        metadata: String? = null,
        agentName: String? = null,
        agentDepth: Int? = null,
        tokensIn: Int? = null,
        tokensOut: Int? = null,
        cost: Double? = null,
    ): ChatMessage {
        return create(
            taskId = taskId,
            role = MessageRole.TOOL,
            content = result,
            metadata = metadata,
            toolCallId = toolCallId,
            subtaskId = subtaskId,
            isSummarized = isSummarized,
            rawOutput = rawOutput,
            agentName = agentName,
            agentDepth = agentDepth,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            cost = cost,
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
                .orderBy(ChatMessagesTable.seq to SortOrder.ASC)
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
                .orderBy(ChatMessagesTable.seq to SortOrder.ASC)
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
     * Ordering is consistent with [findByTaskId]: seq ASC.
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

            val pivotSeq = pivot[ChatMessagesTable.seq]

            val deleted = ChatMessagesTable.deleteWhere {
                (ChatMessagesTable.taskId eq taskId) and (ChatMessagesTable.seq greaterEq pivotSeq)
            }

            logger.info {
                "Deleted $deleted messages from pivot (inclusive): taskId=$taskId, fromMessageId=$fromMessageId"
            }

            deleted
        }
    }

    /**
     * Find messages for a specific agent instance within a multi-agent session.
     */
    fun findByAgentInstanceId(agentInstanceId: String): List<ChatMessage> {
        return transaction {
            ChatMessagesTable.selectAll()
                .where { ChatMessagesTable.agentInstanceId eq agentInstanceId }
                .orderBy(ChatMessagesTable.seq to SortOrder.ASC)
                .map { rowToChatMessage(it) }
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
            agentInstanceId = row[ChatMessagesTable.agentInstanceId],
            agentName = row[ChatMessagesTable.agentName],
            agentDepth = row[ChatMessagesTable.agentDepth],
            role = row[ChatMessagesTable.role],
            content = row[ChatMessagesTable.content],
            thinking = row[ChatMessagesTable.thinking],
            metadata = row[ChatMessagesTable.metadata],
            toolCalls = ToolCallData.fromJsonList(row[ChatMessagesTable.toolCallsJson]),
            toolCallId = row[ChatMessagesTable.toolCallId],
            subtaskId = row[ChatMessagesTable.subtaskId],
            isSummarized = row[ChatMessagesTable.isSummarized],
            rawOutput = row[ChatMessagesTable.rawOutput],
            tokensIn = row[ChatMessagesTable.tokensIn],
            tokensOut = row[ChatMessagesTable.tokensOut],
            cost = row[ChatMessagesTable.cost],
            createdAt = row[ChatMessagesTable.createdAt]
        )
    }
}
