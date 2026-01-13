package pl.jclab.refio.core.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import java.util.UUID

/**
 * Step creator enum
 * Tracks whether a step was created by LLM or manually by user
 */
enum class StepCreator {
    LLM,    // Created by LLM during plan generation
    USER    // Manually added by user via UI
}

/**
 * Plan steps table definition using Exposed ORM DSL
 * Represents individual steps within a plan specification
 * Unlike Subtasks, these are editable and don't track execution state
 */
object PlanStepsTable : Table("plan_steps") {
    val id = varchar("id", 128).clientDefault { UUID.randomUUID().toString() }
    val planId = varchar("plan_id", 128)
        .references(PlansTable.id, onDelete = ReferenceOption.CASCADE)
    val orderIndex = integer("order_index")
    val kind = varchar("kind", 50)  // Tool name (read_file, code_editing, etc.)
    val description = text("description")
    val paramsJson = text("params_json").nullable()  // Suggested tool parameters as JSON
    val isWriteOp = bool("is_write_op").default(false)  // Whether this is a write operation
    val createdBy = enumerationByName<StepCreator>("created_by", 20).default(StepCreator.LLM)
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_plan_steps_plan", false, planId, orderIndex)
        uniqueIndex("uk_plan_order", planId, orderIndex)
    }
}

/**
 * Plan step data class for results
 * Represents a single step in a plan specification
 */
data class PlanStep(
    val id: String,
    val planId: String,
    val orderIndex: Int,
    val kind: String,
    val description: String,
    val paramsJson: String?,
    val isWriteOp: Boolean,
    val createdBy: StepCreator,
    val createdAt: Long,
    val updatedAt: Long
)
