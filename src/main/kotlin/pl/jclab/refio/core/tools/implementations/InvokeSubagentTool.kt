package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.services.AgentTurnLoop
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("InvokeSubagentTool")

class InvokeSubagentTool(
    private val subagentRouterProvider: () -> SubagentRouter?,
    private val runTurnCallback: suspend (TurnRequest, AgentTurnLoop.TurnEventListener?) -> TurnResult,
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
        val turnListener = params["_turn_event_listener"] as? AgentTurnLoop.TurnEventListener

        if (parentChain.any { it.equals(subagentName, ignoreCase = true) }) {
            return ToolResult.error("Subagent recursion detected for '$subagentName'")
        }

        val mode = runCatching {
            TaskMode.valueOf(params["_mode"]?.toString() ?: TaskMode.AGENT.name)
        }.getOrDefault(TaskMode.AGENT)

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
                parentRunId = parentRunId,
                depth = childDepth,
                subagentChain = parentChain
            )
        )

        return try {
            val result = runTurnCallback(request, turnListener)
            if (!result.success) {
                ToolResult.error("Subagent '$subagentName' failed: ${result.response}")
            } else {
                ToolResult.success(
                    output = result.response,
                    metadata = mapOf(
                        "subagent" to subagentName,
                        "depth" to childDepth,
                        "iterations" to result.iterations,
                        "tokens_in" to result.tokensIn,
                        "tokens_out" to result.tokensOut,
                        "cost" to result.cost
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
        val base = "Invoke a specialized subagent to solve part of the task"
        val router = subagentRouterProvider() ?: return "$base. Available subagents: none."

        val subagents = runCatching { router.listSubagents(includeDisabled = false) }
            .getOrElse {
                logger.warn(it) { "[INVOKE_SUBAGENT] Failed to load subagent list for tool description" }
                return "$base. Available subagents: unavailable."
            }

        if (subagents.isEmpty()) {
            return "$base. Available subagents: none."
        }

        val entries = subagents.joinToString("; ") { subagent ->
            val tools = if (subagent.tools.isNullOrEmpty()) {
                "tools=inherit"
            } else {
                "tools=${subagent.tools.joinToString(",")}"
            }
            "${subagent.name}: ${subagent.description} ($tools)"
        }

        return "$base. Available subagents: $entries"
    }
}
