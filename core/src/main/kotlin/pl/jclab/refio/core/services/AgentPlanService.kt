package pl.jclab.refio.core.services

import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.utils.GsonInstance
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

/** Where the main agent's plan is kept between processes, as the JSON [AgentPlanService] writes. */
interface PlanStore {
    fun savePlan(taskId: String, json: String?)
    fun loadPlan(taskId: String): String?
}

/**
 * In-memory service for managing agent execution plans.
 *
 * Each plan is keyed by taskId (optionally scoped by agentId).
 * The orchestrator can view plans from all agents via [getAllPlansForTask].
 * Plans are injected into context via [buildPlanSection].
 *
 * The main agent's plan (agentId null) is also written to [planStore] on every change and read
 * back when it is not in memory, so a task that outlives the process keeps its plan. A subagent's
 * plan lives only as long as its run.
 */
class AgentPlanService(private val planStore: PlanStore? = null) {

    private val plans = ConcurrentHashMap<String, MutableList<AgentPlanStep>>()
    private val planLocks = ConcurrentHashMap<String, Any>()
    /** Tasks whose stored plan was already looked up; the prompt asks for the plan every iteration. */
    private val lookedUp = ConcurrentHashMap.newKeySet<String>()

    fun setPlan(taskId: String, agentId: String?, steps: List<AgentPlanStep>) {
        val key = planKey(taskId, agentId)
        val lock = planLocks.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            plans[key] = steps.toMutableList()
            if (agentId == null) {
                store(taskId, steps)
            }
        }
        logger.info { "Plan set for $key with ${steps.size} steps" }
    }

    fun getPlan(taskId: String, agentId: String? = null): List<AgentPlanStep>? {
        val key = planKey(taskId, agentId)
        val lock = planLocks.computeIfAbsent(key) { Any() }
        return synchronized(lock) { (plans[key] ?: restoreInto(key, taskId, agentId))?.toList() }
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
            val plan = plans[key] ?: restoreInto(key, taskId, agentId) ?: return null
            if (stepIndex !in plan.indices) return null
            plan[stepIndex] = plan[stepIndex].copy(
                status = status,
                note = note ?: plan[stepIndex].note,
                updatedAt = Instant.now()
            )
            if (agentId == null) {
                store(taskId, plan)
            }
            logger.info { "Step $stepIndex in $key → $status" }
            plan[stepIndex]
        }
    }

    fun getAllPlansForTask(taskId: String): Map<String, List<AgentPlanStep>> {
        getPlan(taskId) // brings a stored main-agent plan back into memory
        return plans.filterKeys { it.startsWith("$taskId:") || it == taskId }
            .mapValues { it.value.toList() }
    }

    /**
     * Build a context section showing plans for a given task.
     *
     * When [agentId] is null (the top-level orchestrator), every agent's plan for the task is
     * shown so the orchestrator can see how far each subagent got. When [agentId] is set (a
     * subagent), only that agent's own plan is shown: a subagent has no use for its parent's or
     * siblings' plans, and seeing their steps can trick a weak model into calling `tasks(update)`
     * on a step it never created (its own plan is empty), which fails with "Step N not found".
     * Returns empty string if there is no plan to show.
     */
    fun buildPlanSection(taskId: String, agentId: String? = null): String {
        val allPlans = getAllPlansForTask(taskId)
        val scoped = if (agentId != null) {
            val ownKey = planKey(taskId, agentId)
            allPlans.filterKeys { it == ownKey }
        } else {
            allPlans
        }
        if (scoped.isEmpty()) return ""

        return buildString {
            appendLine("<agent_plans>")
            scoped.forEach { (key, steps) ->
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
        lookedUp.clear()
    }

    private fun planKey(taskId: String, agentId: String?): String =
        if (agentId != null) "$taskId:$agentId" else taskId

    /** One stored step. The position in the list is the step's index. */
    private data class StoredStep(val title: String, val description: String?, val status: String, val note: String?)

    /** Best effort: a failed write keeps the plan in memory and only loses it at the next restart. */
    private fun store(taskId: String, steps: List<AgentPlanStep>) {
        val store = planStore ?: return
        runCatching {
            store.savePlan(taskId, GsonInstance.gson.toJson(steps.map { StoredStep(it.title, it.description, it.status.name, it.note) }))
        }.onFailure { logger.warn { "Could not store the plan of task $taskId: ${it.message}" } }
    }

    /** Reads the main agent's stored plan into memory; called under the plan's lock. */
    private fun restoreInto(key: String, taskId: String, agentId: String?): MutableList<AgentPlanStep>? {
        if (agentId != null || !lookedUp.add(taskId)) return null
        val store = planStore ?: return null
        val steps = runCatching {
            store.loadPlan(taskId)?.let { json ->
                GsonInstance.gson.fromJson(json, Array<StoredStep>::class.java)?.mapIndexed { index, step ->
                    AgentPlanStep(
                        index = index,
                        title = step.title,
                        description = step.description,
                        status = PlanStepStatus.fromString(step.status) ?: PlanStepStatus.PENDING,
                        note = step.note
                    )
                }
            }
        }.onFailure { logger.warn { "Could not read the stored plan of task $taskId: ${it.message}" } }.getOrNull()
            ?: return null
        return steps.toMutableList().also { plans[key] = it }
    }
}
