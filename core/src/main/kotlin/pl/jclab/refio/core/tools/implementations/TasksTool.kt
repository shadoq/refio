package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.services.AgentPlanService
import pl.jclab.refio.core.services.AgentPlanStep
import pl.jclab.refio.core.services.PlanStepStatus
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolInternalParams
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult

/**
 * Unified tool for managing agent execution plans.
 *
 * Actions:
 * - plan: Create or replace an execution plan with numbered steps
 * - update: Mark a step as in_progress/completed/failed/skipped with optional notes
 * - list: Show current plan status with all steps
 */
class TasksTool(
    private val agentPlanService: AgentPlanService
) : Tool {
    override val name = "tasks"
    override val description = """Manage execution plan for the current task.
Actions: plan (create steps), update (mark step status), list (show progress).
Use plan BEFORE starting complex work to organize approach.
The plan is visible to the orchestrating agent."""
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.SYSTEM

    override fun getParameterSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "action" to mapOf(
                "type" to "string",
                "enum" to listOf("plan", "update", "list"),
                "description" to "Action to perform"
            ),
            "steps" to mapOf(
                "type" to "array",
                "description" to "List of plan steps in execution order. Required for plan action.",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "title" to mapOf("type" to "string", "description" to "Short step title"),
                        "description" to mapOf("type" to "string", "description" to "What this step involves")
                    ),
                    "required" to listOf("title")
                )
            ),
            "step_index" to mapOf(
                "type" to "integer",
                "description" to "0-based step index. Required for update action."
            ),
            "status" to mapOf(
                "type" to "string",
                "enum" to listOf("in_progress", "completed", "failed", "skipped"),
                "description" to "New status for the step. Required for update action."
            ),
            "note" to mapOf(
                "type" to "string",
                "description" to "Optional note about the result or blocker"
            )
        ),
        "required" to listOf("action")
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"] as? String
            ?: return ToolResult.error("action required")

        return when (action) {
            "plan" -> handlePlan(params)
            "update" -> handleUpdate(params)
            "list" -> handleList(params)
            else -> ToolResult.error("Unknown action: $action. Use: plan, update, list")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handlePlan(params: Map<String, Any>): ToolResult {
        val taskId = params[ToolInternalParams.TASK_ID] as? String
            ?: return ToolResult.error("No task context")
        val agentId = params[ToolInternalParams.AGENT_ID] as? String
        val steps = params["steps"] as? List<*>
            ?: return ToolResult.error("steps required for plan action")

        val planSteps = steps.mapIndexed { index, step ->
            val map = step as? Map<*, *>
                ?: return ToolResult.error("Invalid step at index $index")
            AgentPlanStep(
                index = index,
                title = map["title"] as? String
                    ?: return ToolResult.error("Step $index missing title"),
                description = map["description"] as? String,
                status = PlanStepStatus.PENDING
            )
        }

        agentPlanService.setPlan(taskId, agentId, planSteps)

        return ToolResult(
            success = true,
            output = "Plan created with ${planSteps.size} steps:\n" +
                planSteps.joinToString("\n") { "  [ ] ${it.index + 1}. ${it.title}" },
            metadata = mapOf("plan_steps" to planSteps.size)
        )
    }

    private fun handleUpdate(params: Map<String, Any>): ToolResult {
        val taskId = params[ToolInternalParams.TASK_ID] as? String
            ?: return ToolResult.error("No task context")
        val agentId = params[ToolInternalParams.AGENT_ID] as? String
        val stepIndex = (params["step_index"] as? Number)?.toInt()
            ?: return ToolResult.error("step_index required for update action")
        val status = PlanStepStatus.fromString(params["status"] as? String ?: "")
            ?: return ToolResult.error("Invalid status. Use: in_progress, completed, failed, skipped")
        val note = params["note"] as? String

        val updated = agentPlanService.updateStep(taskId, agentId, stepIndex, status, note)
            ?: return ToolResult.error("Step $stepIndex not found. Create a plan first with action='plan'.")

        val plan = agentPlanService.getPlan(taskId, agentId)
        val completed = plan?.count { it.status == PlanStepStatus.COMPLETED } ?: 0
        val total = plan?.size ?: 0

        return ToolResult(
            success = true,
            output = "Step ${stepIndex + 1} '${updated.title}' → $status. Progress: $completed/$total completed." +
                (if (note != null) "\nNote: $note" else ""),
            metadata = mapOf("progress" to "$completed/$total")
        )
    }

    private fun handleList(params: Map<String, Any>): ToolResult {
        val taskId = params[ToolInternalParams.TASK_ID] as? String
            ?: return ToolResult.error("No task context")
        val agentId = params[ToolInternalParams.AGENT_ID] as? String
        val plan = agentPlanService.getPlan(taskId, agentId)

        if (plan.isNullOrEmpty()) {
            return ToolResult(success = true, output = "No plan created yet. Use action='plan' to create one.")
        }

        val output = buildString {
            appendLine("## Current Plan")
            plan.forEach { step ->
                appendLine("${step.status.icon} ${step.index + 1}. ${step.title}")
                step.note?.let { appendLine("     Note: $it") }
            }
            val completed = plan.count { it.status == PlanStepStatus.COMPLETED }
            appendLine("\nProgress: $completed/${plan.size}")
        }

        return ToolResult(success = true, output = output)
    }
}
