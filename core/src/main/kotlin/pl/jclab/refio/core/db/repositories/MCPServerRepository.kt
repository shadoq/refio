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
import pl.jclab.refio.core.db.DatabaseFactory
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
        DatabaseFactory.dbWriteQuery {
            val now = System.currentTimeMillis()
            val configJson = gson.toJson(config)

            // Issue UPDATE first so SQLite acquires a RESERVED write lock immediately
            // (avoiding read-to-write upgrade SQLITE_BUSY_SNAPSHOT races across processes).
            val updated = MCPServersTable.update({
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

            if (updated == 0) {
                try {
                    MCPServersTable.insert {
                        it[id] = config.id
                        it[MCPServersTable.projectId] = projectId
                        it[MCPServersTable.configJson] = configJson
                        it[MCPServersTable.enabled] = config.enabled
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                } catch (e: Exception) {
                    if (!DatabaseFactory.isSqliteUniqueConstraintError(e)) throw e
                    MCPServersTable.update({
                        if (projectId != null) {
                            (MCPServersTable.id eq config.id) and (MCPServersTable.projectId eq projectId)
                        } else {
                            (MCPServersTable.id eq config.id) and MCPServersTable.projectId.isNull()
                        }
                    }) {
                        it[MCPServersTable.configJson] = configJson
                        it[MCPServersTable.enabled] = config.enabled
                        it[updatedAt] = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    fun delete(projectId: String?, serverId: String) {
        DatabaseFactory.dbWriteQuery {
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
