package pl.jclab.refio.core.db.repositories

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.core.db.SnapshotGroup
import pl.jclab.refio.core.db.SnapshotGroupsTable
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("SnapshotGroupRepository")

/**
 * Repository for [SnapshotGroupsTable]. A group aggregates the file snapshots
 * captured before a single tool execution.
 */
class SnapshotGroupRepository {

    fun create(taskId: String, subtaskId: String?): SnapshotGroup {
        return transaction {
            val id = SnapshotGroupsTable.insert {
                it[SnapshotGroupsTable.taskId] = taskId
                it[SnapshotGroupsTable.subtaskId] = subtaskId
            } get SnapshotGroupsTable.id

            logger.info { "Created snapshot group: id=$id, taskId=$taskId, subtaskId=$subtaskId" }
            findById(id) ?: error("Failed to retrieve created snapshot group $id")
        }
    }

    fun findById(id: String): SnapshotGroup? = transaction {
        SnapshotGroupsTable.selectAll()
            .where { SnapshotGroupsTable.id eq id }
            .map(::toGroup)
            .singleOrNull()
    }

    fun findBySubtaskId(subtaskId: String): SnapshotGroup? = transaction {
        SnapshotGroupsTable.selectAll()
            .where { SnapshotGroupsTable.subtaskId eq subtaskId }
            .orderBy(SnapshotGroupsTable.createdAt to SortOrder.DESC)
            .limit(1)
            .map(::toGroup)
            .singleOrNull()
    }

    private fun toGroup(row: ResultRow) = SnapshotGroup(
        id = row[SnapshotGroupsTable.id],
        taskId = row[SnapshotGroupsTable.taskId],
        subtaskId = row[SnapshotGroupsTable.subtaskId],
        createdAt = row[SnapshotGroupsTable.createdAt]
    )
}
