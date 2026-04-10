package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.StreamChunk
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.turn.TurnEventListener
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult

private val logger = dualLogger("DelegateToStrongModelTool")

private const val MAX_ITERATIONS_TOOL_MODE = 25

private val SINGLE_SHOT_SYSTEM_PROMPT = """
You are an expert software engineer assistant called upon for complex tasks.
You receive a specific task with optional context from another AI agent that
determined this problem needs deeper expertise.

Provide thorough, precise, and actionable responses. Include code examples
where relevant. Be direct — the requesting agent will interpret your response
and act on it.
""".trimIndent()

class DelegateToStrongModelTool(
    private val llmClient: LLMClient,
    private val configServiceProvider: () -> ConfigService,
    private val runTurnCallback: suspend (TurnRequest, TurnEventListener?, StreamCallback?) -> TurnResult
) : Tool {

    override val name = "delegate_to_strong_model"
    override val description = "Delegate a complex task to a stronger, more capable model. " +
        "Use when the task requires deeper reasoning, you've attempted a solution but the result is unsatisfactory, " +
        "the problem involves complex architectural decisions or subtle bugs, or you need expert-level analysis. " +
        "Default: single-shot (text response, no tools). Set allow_tools=true for full agent mode. " +
        "The strong model receives ONLY what you pass — be explicit in your task description."
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.SYSTEM

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val taskId = params["_task_id"]?.toString()
            ?: return ToolResult.error("Missing internal parameter: _task_id")
        val task = params["task"]?.toString()?.trim()
            ?: return ToolResult.error("Missing required parameter: task")
        if (task.isBlank()) {
            return ToolResult.error("Parameter 'task' cannot be empty")
        }

        val context = params["context"]?.toString()?.trim()
        val allowTools = (params["allow_tools"] as? Boolean) ?: false
        val responseFormat = params["response_format"]?.toString()?.trim() ?: "text"

        val configService = configServiceProvider()
        val strongModel = configService.getStrongModel()
            ?: return ToolResult.error("Strong model not configured")

        val (modelId, provider) = strongModel

        logger.info {
            "[DELEGATE_STRONG] task=${task.take(80)}, allowTools=$allowTools, model=$provider/$modelId"
        }

        return if (allowTools) {
            executeWithTools(taskId, task, context, modelId, provider, params)
        } else {
            executeSingleShot(task, context, modelId, provider, responseFormat, taskId)
        }
    }

    private suspend fun executeSingleShot(
        task: String,
        context: String?,
        modelId: String,
        provider: String,
        responseFormat: String,
        taskId: String
    ): ToolResult {
        val userContent = if (!context.isNullOrBlank()) {
            "$task\n\n--- Context ---\n$context"
        } else {
            task
        }

        val messages = listOf(LLMMessage(role = "user", content = userContent))

        val formatMap = if (responseFormat == "json") {
            mapOf("type" to "json_object")
        } else {
            null
        }

        val response = try {
            llmClient.complete(
                provider = provider,
                model = modelId,
                messages = messages,
                systemPrompt = SINGLE_SHOT_SYSTEM_PROMPT,
                maxTokens = 16384,
                temperature = 0.3,
                responseFormat = formatMap,
                taskId = taskId,
                source = "DelegateToStrongModelTool",
                thinking = false,
                stream = false
            )
        } catch (e: Exception) {
            logger.error(e) { "[DELEGATE_STRONG] Single-shot call failed: ${e.message}" }
            return ToolResult.error("Strong model call failed: ${e.message}")
        }

        logger.info {
            "[DELEGATE_STRONG] Single-shot complete: tokens_in=${response.usage.inputTokens}, " +
                "tokens_out=${response.usage.outputTokens}, cost=${response.cost}"
        }

        return ToolResult.success(
            output = response.content,
            metadata = mapOf(
                "mode" to "single_shot",
                "provider" to provider,
                "model" to modelId,
                "tokens_in" to response.usage.inputTokens,
                "tokens_out" to response.usage.outputTokens,
                "cost" to response.cost
            )
        )
    }

    private suspend fun executeWithTools(
        taskId: String,
        task: String,
        context: String?,
        modelId: String,
        provider: String,
        params: Map<String, Any>
    ): ToolResult {
        val parentDepth = (params["_parent_depth"] as? Number)?.toInt() ?: 0
        val parentRunId = params["_parent_run_id"]?.toString()
        val parentChain = (params["_subagent_chain"] as? List<*>)
            ?.mapNotNull { it?.toString() }
            .orEmpty()
        val turnListener = params["_turn_event_listener"] as? TurnEventListener

        val parentMode = runCatching {
            TaskMode.valueOf(params["_mode"]?.toString() ?: TaskMode.PLAN.name)
        }.getOrDefault(TaskMode.PLAN)

        val streamCallback: StreamCallback? = if (turnListener != null) {
            { chunk: StreamChunk ->
                turnListener.onStreamChunk(taskId, chunk.delta, chunk.accumulated)
            }
        } else {
            null
        }

        val userInput = if (!context.isNullOrBlank()) {
            "$task\n\n--- Context ---\n$context"
        } else {
            task
        }
        val childDepth = parentDepth + 1

        logger.info {
            "[DELEGATE_STRONG] Tool-enabled mode: depth=$childDepth, parentMode=$parentMode"
        }

        val request = TurnRequest(
            taskId = taskId,
            userInput = userInput,
            mode = parentMode,
            executionMode = ExecutionMode.AUTO,
            model = modelId,
            provider = provider,
            runProfile = TurnRunProfile.SUBAGENT,
            profileOverrides = TurnProfileOverrides(
                subagentName = "strong-model",
                modelOverride = modelId,
                providerOverride = provider,
                maxIterationsOverride = MAX_ITERATIONS_TOOL_MODE,
                parentRunId = parentRunId,
                depth = childDepth,
                subagentChain = parentChain + "strong-model"
            )
        )

        return try {
            val result = runTurnCallback(request, turnListener, streamCallback)
            if (!result.success) {
                ToolResult.error("Strong model (tool-enabled) failed: ${result.response}")
            } else {
                ToolResult.success(
                    output = result.response,
                    metadata = mapOf(
                        "mode" to "tool_enabled",
                        "provider" to provider,
                        "model" to modelId,
                        "depth" to childDepth,
                        "iterations" to result.iterations,
                        "tokens_in" to result.tokensIn,
                        "tokens_out" to result.tokensOut,
                        "cost" to result.cost
                    )
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "[DELEGATE_STRONG] Tool-enabled call failed: ${e.message}" }
            ToolResult.error("Strong model (tool-enabled) error: ${e.message}")
        }
    }

    override fun getParameterSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "required" to listOf("task"),
        "properties" to mapOf(
            "task" to mapOf(
                "type" to "string",
                "description" to "Task description for the strong model — what to analyze, solve, or decide. " +
                    "Be explicit: the strong model has no access to conversation history."
            ),
            "context" to mapOf(
                "type" to "string",
                "description" to "Additional context: code fragments, error logs, analysis results, file contents. " +
                    "Optional but recommended for complex tasks."
            ),
            "allow_tools" to mapOf(
                "type" to "boolean",
                "description" to "If true, the strong model gets tools (read_file, code_editing, etc.) and can execute " +
                    "operations autonomously. Default: false (single-shot text response, cheaper and faster)."
            ),
            "response_format" to mapOf(
                "type" to "string",
                "enum" to listOf("text", "json"),
                "description" to "Expected response format. 'text' (default) or 'json'. Only applies to single-shot mode."
            )
        )
    )
}
