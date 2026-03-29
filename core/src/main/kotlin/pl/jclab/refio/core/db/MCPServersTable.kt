package pl.jclab.refio.core.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Table storing MCP server configurations (JSON payload).
 */
object MCPServersTable : Table("mcp_servers") {
    val id = varchar("id", 128)
    val projectId = varchar("project_id", 512).nullable()
    val configJson = text("config_json")
    val enabled = bool("enabled").default(true)
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }

    override val primaryKey = PrimaryKey(id, projectId)

    init {
        index("idx_mcp_servers_project", false, projectId)
    }
}
