package pl.jclab.refio.core.db

import org.jetbrains.exposed.sql.*

/**
 * Config table definition using Exposed ORM DSL
 * Unified configuration storage with scope-based precedence
 */
object ConfigTable : Table("config") {
    val key = varchar("key", 128)
    val value = text("value")  // JSON-serialized value for flexibility
    val scope = enumerationByName<ConfigScope>("scope", 16)  // APP, PROJECT, TASK
    val projectId = varchar("project_id", 512).nullable()
    val taskId = varchar("task_id", 36).references(TasksTable.id, onDelete = ReferenceOption.CASCADE).nullable()

    val description = text("description").nullable()  // Human-readable description
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }

    override val primaryKey = PrimaryKey(key, scope, projectId, taskId)

    init {
        // Index for efficient retrieval by scope
        index("idx_config_scope", false, scope, key)
        // Index for task-specific config
        index("idx_config_task", false, taskId, key)
        // Index for project-specific config
        index("idx_config_project_key", false, projectId, key)
    }
}

/**
 * Config data class for results
 */
data class Config(
    val key: String,
    val value: String,
    val scope: ConfigScope,
    val projectId: String?,
    val taskId: String?,
    val description: String?,
    val createdAt: Long,
    val updatedAt: Long
)
