package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.StreamChunk
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.turn.TurnEventListener
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("InvokeSubagentTool")

class InvokeSubagentTool(
    private val subagentRouterProvider: () -> SubagentRouter?,
    private val runTurnCallback: suspend (TurnRequest, TurnEventListener?, StreamCallback?) -> TurnResult,
    private val configServiceProvider: () -> ConfigService
) : Tool {

    override val name = "invoke_subagent"
    override val description: String
        get() = buildDynamicDescription()
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.DATA_PRODUCING

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val taskId = params["_task_id"]?.toString()
            ?: return ToolResult.error("Missing internal parameter: _task_id")
        val subagentName = params["subagent_name"]?.toString()?.trim()
            ?: return ToolResult.error("Missing required parameter: subagent_name")
        val goal = params["goal"]?.toString()?.trim()
            ?: return ToolResult.error("Missing required parameter: goal")
        if (goal.isBlank()) {
            return ToolResult.error("Parameter 'goal' cannot be empty")
        }

        val parentDepth = (params["_parent_depth"] as? Number)?.toInt() ?: 0
        val parentRunId = params["_parent_run_id"]?.toString()
        val parentChain = (params["_subagent_chain"] as? List<*>)
            ?.mapNotNull { it?.toString() }
            .orEmpty()
        val turnListener = params["_turn_event_listener"] as? TurnEventListener

        // Build a StreamCallback from the turn listener so subagent LLM output streams to the UI
        val streamCallback: StreamCallback? = if (turnListener != null) {
            { chunk: StreamChunk ->
                turnListener.onStreamChunk(taskId, chunk.delta, chunk.accumulated)
            }
        } else {
            null
        }

        if (parentChain.any { it.equals(subagentName, ignoreCase = true) }) {
            return ToolResult.error("Subagent recursion detected for '$subagentName'")
        }

        // Security ceiling: subagent mode can never exceed parent mode.
        // Default to PLAN (not AGENT) if _mode is missing — safe fallback.
        val parentMode = runCatching {
            TaskMode.valueOf(params["_mode"]?.toString() ?: TaskMode.PLAN.name)
        }.getOrDefault(TaskMode.PLAN)
        val mode = parentMode // subagent inherits parent's mode as ceiling

        val executionMode = runCatching {
            ExecutionMode.valueOf(params["_execution_mode"]?.toString() ?: ExecutionMode.AUTO.name)
        }.getOrDefault(ExecutionMode.AUTO)

        val router = subagentRouterProvider()
            ?: return ToolResult.error("Subagent system not available")
        val definition = router.getSubagent(subagentName)
            ?: return ToolResult.error("Subagent not found: $subagentName")
        if (!definition.enabled) {
            return ToolResult.error("Subagent is disabled: $subagentName")
        }

        val configService = configServiceProvider()
        val (resolvedModel, resolvedProvider) = definition.resolveModel(configService)
        val contextRefs = parseContextRefs(params["context_refs"])
        val childDepth = parentDepth + 1

        logger.info {
            "[INVOKE_SUBAGENT] name=$subagentName, taskId=$taskId, depth=$childDepth, parentRunId=${parentRunId ?: "-"}"
        }

        val request = TurnRequest(
            taskId = taskId,
            userInput = goal,
            mode = mode,
            executionMode = executionMode,
            model = resolvedModel,
            provider = resolvedProvider,
            userContextRefs = contextRefs,
            runProfile = TurnRunProfile.SUBAGENT,
            profileOverrides = TurnProfileOverrides(
                subagentName = subagentName,
                systemPromptOverride = definition.systemPrompt,
                allowedTools = definition.allowedTools,
                disallowedTools = definition.disallowedTools,
                modelOverride = resolvedModel,
                providerOverride = resolvedProvider,
                maxIterationsOverride = definition.maxSteps,
                reasoningEffort = definition.reasoningEffort,
                parentRunId = parentRunId,
                depth = childDepth,
                // Keep only ancestors in the chain. The active subagent name is
                // stored separately in subagentName and validated against ancestors.
                subagentChain = parentChain,
                contextProfile = definition.contextProfile
            )
        )

        return try {
            val result = runTurnCallback(request, turnListener, streamCallback)
            if (!result.success) {
                ToolResult.error("Subagent '$subagentName' failed: ${result.response}")
            } else {
                // Include any unanswered questions from the child in the output
                val unansweredQuestions = result.unansweredQuestions.orEmpty()
                val output = if (unansweredQuestions.isNotEmpty()) {
                    val questionsSummary = unansweredQuestions.joinToString("\n") { "  - $it" }
                    "${result.response}\n\n[Subagent '$subagentName' had unanswered questions:]\n$questionsSummary"
                } else {
                    result.response
                }

                ToolResult.success(
                    output = output,
                    metadata = mapOf(
                        "subagent_name" to subagentName,
                        "depth" to childDepth,
                        "iterations" to result.iterations,
                        "tokens_in" to result.tokensIn,
                        "tokens_out" to result.tokensOut,
                        "cost" to result.cost,
                        "unanswered_questions" to unansweredQuestions.size
                    )
                )
            }
        } catch (e: Exception) {
            ToolResult.error("Subagent '$subagentName' error: ${e.message}")
        }
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "required" to listOf("subagent_name", "goal"),
            "properties" to mapOf(
                "subagent_name" to mapOf(
                    "type" to "string",
                    "description" to "Subagent name to invoke"
                ),
                "goal" to mapOf(
                    "type" to "string",
                    "description" to "Task goal for the subagent"
                ),
                "context_refs" to mapOf(
                    "type" to "array",
                    "description" to "Optional context references",
                    "items" to mapOf("type" to "string")
                )
            )
        )
    }

    private fun parseContextRefs(raw: Any?): List<ContextReference> {
        val refs = raw as? List<*> ?: return emptyList()
        return refs.mapNotNull { value ->
            val path = value?.toString()?.trim().orEmpty()
            if (path.isBlank()) null else ContextReference.file(path)
        }
    }

    private fun buildDynamicDescription(): String {
        val router = subagentRouterProvider()
            ?: return "Delegate a task to a specialized subagent. EXPENSIVE (spawns a full turn loop). Available: none."

        val subagents = runCatching { router.listSubagents(includeDisabled = false) }
            .getOrElse {
                logger.warn(it) { "[INVOKE_SUBAGENT] Failed to load subagent list for tool description" }
                return "Delegate a task to a specialized subagent. EXPENSIVE (spawns a full turn loop). Available: unavailable."
            }

        val names = subagents.joinToString("; ") { "\n- ${it.name}: ${it.description}" }
        return "Delegate a task to a specialized subagent. EXPENSIVE (spawns a full turn loop). " +
            "Available: \n$names."
    }
}
