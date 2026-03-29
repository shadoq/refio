package pl.jclab.refio.core.db.repositories

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import pl.jclab.refio.core.db.*
import java.util.UUID

/**
 * Repository for agent instances within multi-agent sessions.
 */
class AgentInstanceRepository {

    fun create(
        sessionId: String,
        name: String,
        taskDescription: String,
        profile: String? = null,
        model: String? = null,
        dependsOn: String? = null,
        taskId: String? = null
    ): AgentInstance = DatabaseFactory.dbQuery {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        AgentInstancesTable.insert {
            it[AgentInstancesTable.id] = id
            it[AgentInstancesTable.sessionId] = sessionId
            it[AgentInstancesTable.taskId] = taskId
            it[AgentInstancesTable.name] = name
            it[AgentInstancesTable.profile] = profile
            it[status] = AgentInstanceStatus.PENDING.name
            it[AgentInstancesTable.model] = model
            it[AgentInstancesTable.taskDescription] = taskDescription
            it[AgentInstancesTable.dependsOn] = dependsOn
            it[createdAt] = now
        }

        AgentInstance(id, sessionId, taskId, name, profile, AgentInstanceStatus.PENDING.name,
            model, taskDescription, dependsOn, null, 0, 0, 0.0, null, null, now)
    }

    fun findById(id: String): AgentInstance? = DatabaseFactory.dbQuery {
        AgentInstancesTable.selectAll().where { AgentInstancesTable.id eq id }
            .map { it.toAgentInstance() }
            .singleOrNull()
    }

    fun findBySessionId(sessionId: String): List<AgentInstance> = DatabaseFactory.dbQuery {
        AgentInstancesTable.selectAll().where { AgentInstancesTable.sessionId eq sessionId }
            .orderBy(AgentInstancesTable.createdAt, SortOrder.ASC)
            .map { it.toAgentInstance() }
    }

    fun updateStatus(id: String, status: AgentInstanceStatus, startedAt: Long? = null, completedAt: Long? = null) =
        DatabaseFactory.dbQuery {
            AgentInstancesTable.update({ AgentInstancesTable.id eq id }) {
                it[AgentInstancesTable.status] = status.name
                if (startedAt != null) it[AgentInstancesTable.startedAt] = startedAt
                if (completedAt != null) it[AgentInstancesTable.completedAt] = completedAt
            }
        }

    fun updateResult(id: String, result: String?, tokensIn: Int, tokensOut: Int, costUsd: Double) =
        DatabaseFactory.dbQuery {
            AgentInstancesTable.update({ AgentInstancesTable.id eq id }) {
                it[AgentInstancesTable.result] = result
                it[AgentInstancesTable.tokensIn] = tokensIn
                it[AgentInstancesTable.tokensOut] = tokensOut
                it[AgentInstancesTable.costUsd] = costUsd
            }
        }

    fun delete(id: String) = DatabaseFactory.dbQuery {
        AgentInstancesTable.deleteWhere { AgentInstancesTable.id eq id }
    }

    private fun ResultRow.toAgentInstance() = AgentInstance(
        id = this[AgentInstancesTable.id],
        sessionId = this[AgentInstancesTable.sessionId],
        taskId = this[AgentInstancesTable.taskId],
        name = this[AgentInstancesTable.name],
        profile = this[AgentInstancesTable.profile],
        status = this[AgentInstancesTable.status],
        model = this[AgentInstancesTable.model],
        taskDescription = this[AgentInstancesTable.taskDescription],
        dependsOn = this[AgentInstancesTable.dependsOn],
        result = this[AgentInstancesTable.result],
        tokensIn = this[AgentInstancesTable.tokensIn],
        tokensOut = this[AgentInstancesTable.tokensOut],
        costUsd = this[AgentInstancesTable.costUsd],
        startedAt = this[AgentInstancesTable.startedAt],
        completedAt = this[AgentInstancesTable.completedAt],
        createdAt = this[AgentInstancesTable.createdAt]
    )
}
