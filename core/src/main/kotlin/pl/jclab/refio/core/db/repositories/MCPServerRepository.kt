package pl.jclab.refio.core.db.repositories

import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.core.context.mcp.MCPServerConfig
import pl.jclab.refio.core.db.MCPServersTable
import pl.jclab.refio.core.utils.GsonInstance
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("MCPServerRepository")

/**
 * Repository for persisting MCP server configurations.
 */
class MCPServerRepository {
    private val gson = GsonInstance.gson

    fun getAll(projectId: String?): List<MCPServerConfig> = transaction {
        val query = if (projectId != null) {
            MCPServersTable.selectAll().where { MCPServersTable.projectId eq projectId }
        } else {
            MCPServersTable.selectAll().where { MCPServersTable.projectId.isNull() }
        }
        query.mapNotNull { row ->
            runCatching {
                val config = gson.fromJson(row[MCPServersTable.configJson], MCPServerConfig::class.java)
                config.copy(enabled = row[MCPServersTable.enabled])
            }.onFailure { e ->
                logger.error(e) { "Failed to parse MCP config for ${row[MCPServersTable.id]}" }
            }.getOrNull()
        }
    }

    fun upsert(projectId: String?, config: MCPServerConfig) {
        transaction {
            val existing = if (projectId != null) {
                MCPServersTable.selectAll().where {
                    (MCPServersTable.id eq config.id) and (MCPServersTable.projectId eq projectId)
                }
            } else {
                MCPServersTable.selectAll().where {
                    (MCPServersTable.id eq config.id) and MCPServersTable.projectId.isNull()
                }
            }.firstOrNull()

            val now = System.currentTimeMillis()
            val configJson = gson.toJson(config)

            if (existing == null) {
                MCPServersTable.insert {
                    it[id] = config.id
                    it[MCPServersTable.projectId] = projectId
                    it[MCPServersTable.configJson] = configJson
                    it[MCPServersTable.enabled] = config.enabled
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            } else {
                MCPServersTable.update({
                    if (projectId != null) {
                        (MCPServersTable.id eq config.id) and (MCPServersTable.projectId eq projectId)
                    } else {
                        (MCPServersTable.id eq config.id) and MCPServersTable.projectId.isNull()
                    }
                }) {
                    it[MCPServersTable.configJson] = configJson
                    it[MCPServersTable.enabled] = config.enabled
                    it[updatedAt] = now
                }
            }
        }
    }

    fun delete(projectId: String?, serverId: String) {
        transaction {
            MCPServersTable.deleteWhere {
                if (projectId != null) {
                    (MCPServersTable.id eq serverId) and (MCPServersTable.projectId eq projectId)
                } else {
                    (MCPServersTable.id eq serverId) and MCPServersTable.projectId.isNull()
                }
            }
        }
    }
}
