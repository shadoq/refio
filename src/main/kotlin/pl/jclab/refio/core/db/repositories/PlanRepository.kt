package pl.jclab.refio.core.db.repositories

import pl.jclab.refio.core.db.*
import pl.jclab.refio.services.logging.dualLogger
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = dualLogger("PlanRepository")

/**
 * Repository for Plan database operations
 * Plans are specifications/blueprints created in PLAN mode sessions
 */
class PlanRepository {

    /**
     * Create a new plan
     */
    fun create(
        sessionId: String,
        name: String,
        description: String? = null,
        status: PlanStatus = PlanStatus.DRAFT
    ): Plan {
        return transaction {
            val planId = PlansTable.insert {
                it[PlansTable.sessionId] = sessionId
                it[PlansTable.name] = name
                it[PlansTable.description] = description
                it[PlansTable.status] = status
            } get PlansTable.id

            logger.info { "Created plan: id=$planId, sessionId=$sessionId, name=$name" }

            findById(planId) ?: throw IllegalStateException("Failed to retrieve created plan")
        }
    }

    /**
     * Find plan by ID
     */
    fun findById(id: String): Plan? {
        return transaction {
            PlansTable.selectAll()
                .where { PlansTable.id eq id }
                .map { rowToPlan(it) }
                .singleOrNull()
        }
    }

    /**
     * Find plan by session ID
     * Returns the latest plan for a session (highest version)
     */
    fun findBySessionId(sessionId: String): Plan? {
        return transaction {
            PlansTable.selectAll()
                .where { PlansTable.sessionId eq sessionId }
                .orderBy(PlansTable.version to SortOrder.DESC)
                .limit(1)
                .map { rowToPlan(it) }
                .singleOrNull()
        }
    }

    /**
     * Find all plans for a session
     * Useful for viewing plan history/versions
     */
    fun findAllBySessionId(sessionId: String): List<Plan> {
        return transaction {
            PlansTable.selectAll()
                .where { PlansTable.sessionId eq sessionId }
                .orderBy(PlansTable.version to SortOrder.DESC)
                .map { rowToPlan(it) }
        }
    }

    /**
     * Update plan metadata
     * @return Updated plan
     */
    fun update(
        id: String,
        name: String? = null,
        description: String? = null,
        status: PlanStatus? = null,
        finalizedAt: Long? = null
    ): Plan {
        return transaction {
            PlansTable.update({ PlansTable.id eq id }) {
                name?.let { value -> it[PlansTable.name] = value }
                description?.let { value -> it[PlansTable.description] = value }
                status?.let { value -> it[PlansTable.status] = value }
                finalizedAt?.let { value -> it[PlansTable.finalizedAt] = value }
                it[updatedAt] = System.currentTimeMillis()
            }

            logger.info { "Updated plan: id=$id" }

            findById(id) ?: throw IllegalStateException("Plan not found after update")
        }
    }

    /**
     * Increment plan version
     * Used when plan is modified (steps added/removed/changed)
     * @return Updated plan with incremented version
     */
    fun incrementVersion(id: String): Plan {
        return transaction {
            val currentPlan = findById(id)
                ?: throw IllegalArgumentException("Plan not found: $id")

            PlansTable.update({ PlansTable.id eq id }) {
                it[version] = currentPlan.version + 1
                it[updatedAt] = System.currentTimeMillis()
            }

            logger.info { "Incremented plan version: id=$id, version=${currentPlan.version + 1}" }

            findById(id) ?: throw IllegalStateException("Plan not found after version increment")
        }
    }

    /**
     * Update plan status
     * Convenience method for status transitions
     */
    fun updateStatus(id: String, newStatus: PlanStatus): Plan {
        return transaction {
            val finalizedAt = if (newStatus == PlanStatus.READY) {
                System.currentTimeMillis()
            } else {
                null
            }

            update(id, status = newStatus, finalizedAt = finalizedAt)
        }
    }

    /**
     * Delete plan
     * Note: Cascades to plan_steps due to ON DELETE CASCADE
     */
    fun delete(id: String) {
        transaction {
            PlansTable.deleteWhere { PlansTable.id eq id }
            logger.info { "Deleted plan: id=$id" }
        }
    }

    /**
     * Check if plan is editable
     * Plans in EXECUTING status cannot be modified
     */
    fun isEditable(id: String): Boolean {
        return transaction {
            val plan = findById(id)
            plan?.status != PlanStatus.EXECUTING
        }
    }

    /**
     * Get execution count for a plan
     * Counts how many AGENT sessions reference this plan
     */
    fun getExecutionCount(id: String): Int {
        return transaction {
            TasksTable.selectAll()
                .where { TasksTable.sourcePlanId eq id }
                .count()
                .toInt()
        }
    }

    /**
     * Convert database row to Plan data class
     */
    private fun rowToPlan(row: ResultRow): Plan {
        return Plan(
            id = row[PlansTable.id],
            sessionId = row[PlansTable.sessionId],
            name = row[PlansTable.name],
            description = row[PlansTable.description],
            status = row[PlansTable.status],
            version = row[PlansTable.version],
            createdAt = row[PlansTable.createdAt],
            updatedAt = row[PlansTable.updatedAt],
            finalizedAt = row[PlansTable.finalizedAt]
        )
    }
}
