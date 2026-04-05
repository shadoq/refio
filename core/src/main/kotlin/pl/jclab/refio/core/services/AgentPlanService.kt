package pl.jclab.refio.core.services

import pl.jclab.refio.core.logging.dualLogger
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = dualLogger("AgentPlanService")

/**
 * Status of a plan step.
 */
enum class PlanStepStatus {
    PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED;

    val icon: String
        get() = when (this) {
            PENDING -> "[ ]"
            IN_PROGRESS -> "[>]"
            COMPLETED -> "[x]"
            FAILED -> "[!]"
            SKIPPED -> "[-]"
        }

    companion object {
        fun fromString(s: String): PlanStepStatus? = entries.find {
            it.name.equals(s, ignoreCase = true) ||
                it.name.replace("_", "").equals(s.replace("_", ""), ignoreCase = true)
        }
    }
}

/**
 * Single step in an agent execution plan.
 */
data class AgentPlanStep(
    val index: Int,
    val title: String,
    val description: String? = null,
    val status: PlanStepStatus = PlanStepStatus.PENDING,
    val note: String? = null,
    val updatedAt: Instant = Instant.now()
)

/**
 * In-memory service for managing agent execution plans.
 *
 * Each plan is keyed by taskId (optionally scoped by agentId).
 * The orchestrator can view plans from all agents via [getAllPlansForTask].
 * Plans are injected into context via [buildPlanSection].
 */
class AgentPlanService {

    private val plans = ConcurrentHashMap<String, MutableList<AgentPlanStep>>()
    private val planLocks = ConcurrentHashMap<String, Any>()

    fun setPlan(taskId: String, agentId: String?, steps: List<AgentPlanStep>) {
        val key = planKey(taskId, agentId)
        val lock = planLocks.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            plans[key] = steps.toMutableList()
        }
        logger.info { "Plan set for $key with ${steps.size} steps" }
    }

    fun getPlan(taskId: String, agentId: String? = null): List<AgentPlanStep>? {
        val key = planKey(taskId, agentId)
        val lock = planLocks.computeIfAbsent(key) { Any() }
        return synchronized(lock) { plans[key]?.toList() }
    }

    fun updateStep(
        taskId: String,
        agentId: String?,
        stepIndex: Int,
        status: PlanStepStatus,
        note: String?
    ): AgentPlanStep? {
        val key = planKey(taskId, agentId)
        val lock = planLocks.computeIfAbsent(key) { Any() }
        return synchronized(lock) {
            val plan = plans[key] ?: return null
            if (stepIndex !in plan.indices) return null
            plan[stepIndex] = plan[stepIndex].copy(
                status = status,
                note = note ?: plan[stepIndex].note,
                updatedAt = Instant.now()
            )
            logger.info { "Step $stepIndex in $key → $status" }
            plan[stepIndex]
        }
    }

    fun getAllPlansForTask(taskId: String): Map<String, List<AgentPlanStep>> {
        return plans.filterKeys { it.startsWith("$taskId:") || it == taskId }
            .mapValues { it.value.toList() }
    }

    /**
     * Build a context section showing all plans for a given task.
     * Returns empty string if no plans exist.
     */
    fun buildPlanSection(taskId: String): String {
        val allPlans = getAllPlansForTask(taskId)
        if (allPlans.isEmpty()) return ""

        return buildString {
            appendLine("<agent_plans>")
            allPlans.forEach { (key, steps) ->
                val agentName = if (key.contains(":")) key.substringAfter(":") else "main"
                appendLine("## Agent: $agentName")
                steps.forEach { step ->
                    appendLine("  ${step.status.icon} ${step.index + 1}. ${step.title}")
                    step.note?.let { appendLine("       Note: $it") }
                }
            }
            appendLine("</agent_plans>")
        }
    }

    /**
     * Clear all plans (for session cleanup).
     */
    fun clear() {
        plans.clear()
    }

    private fun planKey(taskId: String, agentId: String?): String =
        if (agentId != null) "$taskId:$agentId" else taskId
}
