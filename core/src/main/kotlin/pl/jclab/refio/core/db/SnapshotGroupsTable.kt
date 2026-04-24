package pl.jclab.refio.core.db

import org.jetbrains.exposed.sql.*
import java.util.UUID

/**
 * Group of file snapshots taken together before a single write operation.
 * One group per tool execution (typically one subtask). A group can contain
 * snapshots of multiple files. Referenced from [SubtasksTable.snapshotIdBeforeWrite].
 */
object SnapshotGroupsTable : Table("snapshot_groups") {
    val id = varchar("id", 36).clientDefault { UUID.randomUUID().toString() }
    val taskId = varchar("task_id", 36).references(TasksTable.id, onDelete = ReferenceOption.CASCADE)

    // Plain column (no FK) to avoid circular FK headaches with subtasks table.
    // Referential integrity in the other direction: subtasks.snapshot_id_before_write -> snapshot_groups.id.
    val subtaskId = varchar("subtask_id", 36).nullable()

    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_snapshot_groups_task", false, taskId, createdAt)
        index("idx_snapshot_groups_subtask", false, subtaskId)
    }
}

data class SnapshotGroup(
    val id: String,
    val taskId: String,
    val subtaskId: String?,
    val createdAt: Long
)
