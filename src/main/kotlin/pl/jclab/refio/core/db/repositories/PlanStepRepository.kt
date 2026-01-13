package pl.jclab.refio.core.db.repositories

import pl.jclab.refio.core.db.*
import pl.jclab.refio.services.logging.dualLogger
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = dualLogger("PlanStepRepository")

/**
 * Repository for PlanStep database operations
 * Manages individual steps within plan specifications
 */
class PlanStepRepository {

    /**
     * Create a new plan step
     */
    fun create(
        planId: String,
        orderIndex: Int,
        kind: String,
        description: String,
        paramsJson: String? = null,
        isWriteOp: Boolean = false,
        createdBy: StepCreator = StepCreator.LLM
    ): PlanStep {
        return transaction {
            val stepId = PlanStepsTable.insert {
                it[PlanStepsTable.planId] = planId
                it[PlanStepsTable.orderIndex] = orderIndex
                it[PlanStepsTable.kind] = kind
                it[PlanStepsTable.description] = description
                it[PlanStepsTable.paramsJson] = paramsJson
                it[PlanStepsTable.isWriteOp] = isWriteOp
                it[PlanStepsTable.createdBy] = createdBy
            } get PlanStepsTable.id

            logger.info { "Created plan step: id=$stepId, planId=$planId, kind=$kind, orderIndex=$orderIndex" }

            findById(stepId) ?: throw IllegalStateException("Failed to retrieve created plan step")
        }
    }

    /**
     * Create a plan step with automatic shift of existing steps
     * Atomically shifts steps at insertAt position and higher by +1
     *
     * @param planId Plan ID
     * @param insertAt Position to insert (0-based)
     * @param kind Tool name
     * @param description Step description
     * @param paramsJson Optional parameters JSON
     * @param isWriteOp Whether this is a write operation
     * @param createdBy Who created this step
     * @return Created plan step
     */
    fun createWithShift(
        planId: String,
        insertAt: Int,
        kind: String,
        description: String,
        paramsJson: String? = null,
        isWriteOp: Boolean = false,
        createdBy: StepCreator = StepCreator.LLM
    ): PlanStep {
        return transaction {
            // Get all steps at or after insertion point
            val stepsToShift = PlanStepsTable.selectAll()
                .where { (PlanStepsTable.planId eq planId) and (PlanStepsTable.orderIndex greaterEq insertAt) }
                .orderBy(PlanStepsTable.orderIndex to SortOrder.DESC)
                .map { it[PlanStepsTable.id] to it[PlanStepsTable.orderIndex] }

            // Shift steps (process in reverse order to avoid conflicts)
            stepsToShift.forEach { (stepId, currentIndex) ->
                PlanStepsTable.update({ PlanStepsTable.id eq stepId }) {
                    it[orderIndex] = currentIndex + 1
                    it[updatedAt] = System.currentTimeMillis()
                }
            }

            logger.info { "Shifted ${stepsToShift.size} steps for insertion at position $insertAt" }

            // Insert new step at the specified position
            create(planId, insertAt, kind, description, paramsJson, isWriteOp, createdBy)
        }
    }

    /**
     * Find plan step by ID
     */
    fun findById(id: String): PlanStep? {
        return transaction {
            PlanStepsTable.selectAll()
                .where { PlanStepsTable.id eq id }
                .map { rowToPlanStep(it) }
                .singleOrNull()
        }
    }

    /**
     * Find all steps for a plan, ordered by orderIndex
     */
    fun findByPlanId(planId: String): List<PlanStep> {
        return transaction {
            PlanStepsTable.selectAll()
                .where { PlanStepsTable.planId eq planId }
                .orderBy(PlanStepsTable.orderIndex to SortOrder.ASC)
                .map { rowToPlanStep(it) }
        }
    }

    /**
     * Update plan step
     */
    fun update(
        id: String,
        kind: String? = null,
        description: String? = null,
        paramsJson: String? = null,
        isWriteOp: Boolean? = null
    ): PlanStep {
        return transaction {
            PlanStepsTable.update({ PlanStepsTable.id eq id }) {
                kind?.let { value -> it[PlanStepsTable.kind] = value }
                description?.let { value -> it[PlanStepsTable.description] = value }
                if (paramsJson != null) {  // Allow explicit null to clear params
                    it[PlanStepsTable.paramsJson] = paramsJson
                }
                isWriteOp?.let { value -> it[PlanStepsTable.isWriteOp] = value }
                it[updatedAt] = System.currentTimeMillis()
            }

            logger.info { "Updated plan step: id=$id" }

            findById(id) ?: throw IllegalStateException("Plan step not found after update")
        }
    }

    /**
     * Delete plan step
     * Automatically reindexes remaining steps to fill the gap
     */
    fun delete(id: String) {
        transaction {
            val step = findById(id) ?: return@transaction

            // Delete the step
            PlanStepsTable.deleteWhere { PlanStepsTable.id eq id }

            // Reindex remaining steps after deleted position
            val stepsToReindex = PlanStepsTable.selectAll()
                .where { (PlanStepsTable.planId eq step.planId) and (PlanStepsTable.orderIndex greaterEq step.orderIndex) }
                .orderBy(PlanStepsTable.orderIndex to SortOrder.ASC)
                .map { it[PlanStepsTable.id] to it[PlanStepsTable.orderIndex] }

            stepsToReindex.forEach { (stepId, currentIndex) ->
                PlanStepsTable.update({ PlanStepsTable.id eq stepId }) {
                    it[orderIndex] = currentIndex - 1
                    it[updatedAt] = System.currentTimeMillis()
                }
            }

            logger.info { "Deleted plan step: id=$id, reindexed ${stepsToReindex.size} steps" }
        }
    }

    /**
     * Reorder plan steps
     * @param planId Plan ID
     * @param newOrder List of step IDs in desired order
     */
    fun reorder(planId: String, newOrder: List<String>) {
        transaction {
            newOrder.forEachIndexed { index, stepId ->
                PlanStepsTable.update({ PlanStepsTable.id eq stepId }) {
                    it[orderIndex] = index
                    it[updatedAt] = System.currentTimeMillis()
                }
            }

            logger.info { "Reordered ${newOrder.size} steps for plan $planId" }
        }
    }

    /**
     * Swap order of two steps
     * Convenience method for drag-and-drop reordering
     */
    fun swap(step1Id: String, step2Id: String) {
        transaction {
            val step1 = findById(step1Id) ?: throw IllegalArgumentException("Step not found: $step1Id")
            val step2 = findById(step2Id) ?: throw IllegalArgumentException("Step not found: $step2Id")

            if (step1.planId != step2.planId) {
                throw IllegalArgumentException("Steps must belong to the same plan")
            }

            // Swap order indices
            PlanStepsTable.update({ PlanStepsTable.id eq step1Id }) {
                it[orderIndex] = step2.orderIndex
                it[updatedAt] = System.currentTimeMillis()
            }

            PlanStepsTable.update({ PlanStepsTable.id eq step2Id }) {
                it[orderIndex] = step1.orderIndex
                it[updatedAt] = System.currentTimeMillis()
            }

            logger.info { "Swapped steps: $step1Id <-> $step2Id" }
        }
    }

    /**
     * Delete all steps for a plan
     * Used when replacing an entire plan
     */
    fun deleteAllForPlan(planId: String) {
        transaction {
            val count = PlanStepsTable.deleteWhere { PlanStepsTable.planId eq planId }
            logger.info { "Deleted all $count steps for plan $planId" }
        }
    }

    /**
     * Get maximum order index for a plan
     * Used to determine next index when appending steps
     */
    fun getMaxOrderIndex(planId: String): Int {
        return transaction {
            PlanStepsTable.selectAll()
                .where { PlanStepsTable.planId eq planId }
                .map { it[PlanStepsTable.orderIndex] }
                .maxOrNull() ?: -1  // Return -1 if no steps exist
        }
    }

    /**
     * Get step statistics for a plan
     */
    fun getStatistics(planId: String): PlanStepStatistics {
        return transaction {
            val steps = findByPlanId(planId)

            PlanStepStatistics(
                totalSteps = steps.size,
                writeSteps = steps.count { it.isWriteOp },
                readSteps = steps.count { !it.isWriteOp },
                llmCreated = steps.count { it.createdBy == StepCreator.LLM },
                userCreated = steps.count { it.createdBy == StepCreator.USER }
            )
        }
    }

    /**
     * Convert database row to PlanStep data class
     */
    private fun rowToPlanStep(row: ResultRow): PlanStep {
        return PlanStep(
            id = row[PlanStepsTable.id],
            planId = row[PlanStepsTable.planId],
            orderIndex = row[PlanStepsTable.orderIndex],
            kind = row[PlanStepsTable.kind],
            description = row[PlanStepsTable.description],
            paramsJson = row[PlanStepsTable.paramsJson],
            isWriteOp = row[PlanStepsTable.isWriteOp],
            createdBy = row[PlanStepsTable.createdBy],
            createdAt = row[PlanStepsTable.createdAt],
            updatedAt = row[PlanStepsTable.updatedAt]
        )
    }
}

/**
 * Plan step statistics data class
 */
data class PlanStepStatistics(
    val totalSteps: Int,
    val writeSteps: Int,
    val readSteps: Int,
    val llmCreated: Int,
    val userCreated: Int
)
