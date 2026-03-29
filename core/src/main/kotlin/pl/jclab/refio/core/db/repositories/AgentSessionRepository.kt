package pl.jclab.refio.core.db.repositories

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import pl.jclab.refio.core.db.*
import java.util.UUID

/**
 * Repository for multi-agent sessions.
 */
class AgentSessionRepository {

    fun create(
        projectId: String,
        name: String,
        definitionYaml: String? = null
    ): AgentSession = DatabaseFactory.dbQuery {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        AgentSessionsTable.insert {
            it[AgentSessionsTable.id] = id
            it[AgentSessionsTable.projectId] = projectId
            it[AgentSessionsTable.name] = name
            it[status] = "NEW"
            it[AgentSessionsTable.definitionYaml] = definitionYaml
            it[createdAt] = now
        }

        AgentSession(id, projectId, name, "NEW", definitionYaml, now, null)
    }

    fun findById(id: String): AgentSession? = DatabaseFactory.dbQuery {
        AgentSessionsTable.selectAll().where { AgentSessionsTable.id eq id }
            .map { it.toAgentSession() }
            .singleOrNull()
    }

    fun findByProjectId(projectId: String): List<AgentSession> = DatabaseFactory.dbQuery {
        AgentSessionsTable.selectAll().where { AgentSessionsTable.projectId eq projectId }
            .orderBy(AgentSessionsTable.createdAt, SortOrder.DESC)
            .map { it.toAgentSession() }
    }

    fun updateStatus(id: String, status: String, completedAt: Long? = null) = DatabaseFactory.dbQuery {
        AgentSessionsTable.update({ AgentSessionsTable.id eq id }) {
            it[AgentSessionsTable.status] = status
            if (completedAt != null) it[AgentSessionsTable.completedAt] = completedAt
        }
    }

    fun delete(id: String) = DatabaseFactory.dbQuery {
        AgentSessionsTable.deleteWhere { AgentSessionsTable.id eq id }
    }

    private fun ResultRow.toAgentSession() = AgentSession(
        id = this[AgentSessionsTable.id],
        projectId = this[AgentSessionsTable.projectId],
        name = this[AgentSessionsTable.name],
        status = this[AgentSessionsTable.status],
        definitionYaml = this[AgentSessionsTable.definitionYaml],
        createdAt = this[AgentSessionsTable.createdAt],
        completedAt = this[AgentSessionsTable.completedAt]
    )
}
