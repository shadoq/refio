package pl.jclab.refio.core.services.orchestration

import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.StreamChunk
import pl.jclab.refio.core.db.PromptType
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.db.Task
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.JsonExtractor
import pl.jclab.refio.core.llm.JsonParseException
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.services.StepExecutionResult
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("ReflectionEngine")

/**
 * Reflection Engine - analyzes execution results and decides on adaptations.
 *
 * Core of intelligent orchestration:
 * - Calls LLM after each step to analyze results
 * - Determines if plan needs modification
 * - Identifies when user input is needed
 * - Detects when to abort execution
 */
class ReflectionEngine(
    private val llmClient: LLMClient,
    private val promptsService: PromptsService,
    private val configService: ConfigService,
    private val taskRepository: TaskRepository,
    private val subtaskRepository: SubtaskRepository,
    private val toolDescriptionBuilder: pl.jclab.refio.core.prompts.ToolDescriptionBuilder
) {

    /**
     * Reflect on execution result and decide next action.
     *
     * @param task Parent task (for context)
     * @param subtask Completed subtask
     * @param result Execution result
     * @param stream Whether to stream LLM response
     * @param onChunk Callback for streaming chunks
     * @return Reflection decision with actions
     */
    suspend fun reflect(
        task: Task,
        subtask: Subtask,
        result: StepExecutionResult,
        stream: Boolean = false,
        onChunk: StreamCallback? = null
    ): ReflectionDecision {
        logger.info { "[REFLECTION] Analyzing step ${subtask.orderIndex}: ${subtask.description}" }

        // Build context for reflection
        val context = buildReflectionContext(task, subtask, result)

        // Get remaining steps
        val remainingSteps = subtaskRepository.findByStatus(task.id, TaskStatus.PENDING)
            .sortedBy { it.orderIndex }

        // Call LLM with orchestrator prompt
        val decision = callReflectionLLM(task, subtask, result, remainingSteps, context, stream, onChunk)

        logger.info { "[REFLECTION] Decision: ${decision.decision}, actions: ${decision.actions.size}" }

        return decision
    }

    /**
     * Build context for reflection (previous results, current state, etc.)
     */
    private fun buildReflectionContext(
        task: Task,
        subtask: Subtask,
        result: StepExecutionResult
    ): String {
        val parts = mutableListOf<String>()

        // Task goal
        parts.add("**Task Goal:** ${task.name}")
        parts.add("")

        // Completed steps
        val completedSteps = subtaskRepository.findByTaskId(task.id)
            .filter { it.status in listOf(TaskStatus.SUCCESS, TaskStatus.FAILED, TaskStatus.CANCELED) }
            .filter { it.orderIndex < subtask.orderIndex }
            .sortedBy { it.orderIndex }

        if (completedSteps.isNotEmpty()) {
            parts.add("**Previous Steps:**")
            completedSteps.forEach { step ->
                val status = when (step.status) {
                    TaskStatus.SUCCESS -> "✓"
                    TaskStatus.FAILED -> "✗"
                    TaskStatus.CANCELED -> "⏭"
                    else -> "?"
                }
                parts.add("  $status Step ${step.orderIndex}: ${step.description}")

                // Include brief result summary if available
                step.summary?.let { summary ->
                    val preview = if (summary.length > 200) summary.take(200) + "..." else summary
                    parts.add("     Summary: $preview")
                }
            }
            parts.add("")
        }

        // Current step result
        parts.add("**Current Step (Step ${subtask.orderIndex}):**")
        parts.add("  Description: ${subtask.description}")
        parts.add("  Status: ${result.status}")
        parts.add("  Summary: ${result.summary}")
        if (result.error != null) {
            parts.add("  Error: ${result.error}")
        }
        parts.add("")

        return parts.joinToString("\n")
    }

    /**
     * Call LLM to analyze result and decide next action.
     */
    private suspend fun callReflectionLLM(
        task: Task,
        subtask: Subtask,
        result: StepExecutionResult,
        remainingSteps: List<Subtask>,
        context: String,
        stream: Boolean = false,
        onChunk: StreamCallback? = null
    ): ReflectionDecision {
        val (model, provider) = configService.getModel(ModelOperation.PLAN, task.id)

        // Generate tool descriptions for the current task mode
        val toolDescriptions = toolDescriptionBuilder.getToolDescriptions(task.mode, task.id)

        // Build system prompt with orchestrator instructions
        val systemPrompt = promptsService.getSystemPrompt(
            type = PromptType.SYSTEM_ORCHESTRATOR,
            variables = mapOf(
                "task_mode" to task.mode.name,
                "remaining_steps" to remainingSteps.size.toString(),
                "tool_descriptions" to toolDescriptions
            )
        )

        // Build user prompt with context + remaining plan
        val userPrompt = buildReflectionUserPrompt(context, remainingSteps)

        // Read UI state
        val thinkingEnabled = configService.get(ConfigService.KEY_UI_THINKING_ENABLED)?.toBoolean() ?: false
        val noEgressEnabled = configService.get(ConfigService.KEY_UI_NO_EGRESS_ENABLED)?.toBoolean() ?: false

        try {
            val messages = listOf(
                LLMMessage(role = "user", content = userPrompt)
            )

            // RFC 0032: Use unified complete() with stream flag
            val response = llmClient.complete(
                provider = provider,
                model = model,
                messages = messages,
                systemPrompt = systemPrompt,
                maxTokens = configService.getMaxOutputTokens(task.id),
                temperature = 0.3,
                responseFormat = mapOf("type" to "json_object"),
                thinking = thinkingEnabled,
                noEgressEnabled = noEgressEnabled,
                stream = stream,
                onChunk = if (stream) onChunk else null,
                taskId = task.id,
                subtaskId = subtask.id,
                source = "ReflectionEngine"
            )

            val content = response.content
            val usage = response.usage
            val cost = response.cost

            // Update task metrics with LLM costs
            taskRepository.incrementMetrics(
                id = task.id,
                tokensIn = usage.inputTokens,
                tokensOut = usage.outputTokens,
                costUsd = cost
            )

            // Parse JSON response using universal JsonExtractor
            val decisionJson = try {
                JsonExtractor.extractAndParse(content)
            } catch (e: JsonParseException) {
                logger.error { "[REFLECTION] Failed to extract/parse JSON: ${e.message}" }
                logger.error { "[REFLECTION] Content preview: ${content.take(500)}" }
                throw IllegalStateException("LLM returned invalid JSON: ${e.message}", e)
            }

            return parseReflectionDecision(decisionJson)

        } catch (e: Exception) {
            logger.error(e) { "[REFLECTION] Failed to get LLM decision" }

            // Fallback: continue with plan if reflection fails
            return ReflectionDecision(
                decision = DecisionType.CONTINUE,
                reasoning = "Reflection failed: ${e.message}. Continuing with original plan.",
                analysis = "",
                actions = emptyList()
            )
        }
    }

    private fun buildReflectionUserPrompt(
        context: String,
        remainingSteps: List<Subtask>
    ): String {
        val parts = mutableListOf<String>()

        parts.add(context)

        // Remaining plan
        if (remainingSteps.isNotEmpty()) {
            parts.add("**Remaining Steps in Plan:**")
            remainingSteps.forEach { step ->
                parts.add("  ${step.orderIndex}. ${step.description}")
            }
            parts.add("")
        } else {
            parts.add("**Remaining Steps:** None (this was the last step)")
            parts.add("")
        }

        parts.add("**Your Task:**")
        parts.add("Analyze the current step result in context of the task goal and remaining plan.")
        parts.add("Decide what to do next. Return decision in JSON format.")

        return parts.joinToString("\n")
    }

    private fun parseReflectionDecision(json: Map<String, Any>): ReflectionDecision {
        val decisionStr = json["decision"] as? String
            ?: throw IllegalArgumentException("Missing 'decision' field")

        val decision = try {
            DecisionType.valueOf(decisionStr.uppercase())
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid decision: $decisionStr. Must be one of: CONTINUE, MODIFY_PLAN, ASK_USER, ABORT")
        }

        val reasoning = json["reasoning"] as? String ?: ""
        val analysis = json["analysis"] as? String ?: ""

        @Suppress("UNCHECKED_CAST")
        val actionsJson = json["actions"] as? List<Map<String, Any>> ?: emptyList()

        val actions = actionsJson.mapNotNull { actionJson ->
            try {
                parseReflectionAction(actionJson)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse action: $actionJson" }
                null
            }
        }

        return ReflectionDecision(
            decision = decision,
            reasoning = reasoning,
            analysis = analysis,
            actions = actions,
            question = json["question"] as? String,
            questionOptions = (json["question_options"] as? List<*>)?.mapNotNull { it as? String }
        )
    }

    private fun parseReflectionAction(json: Map<String, Any>): ReflectionAction {
        val typeStr = json["type"] as? String
            ?: throw IllegalArgumentException("Missing 'type' field in action")

        val type = try {
            ActionType.valueOf(typeStr.uppercase())
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid action type: $typeStr")
        }

        return when (type) {
            ActionType.ADD_STEP -> ReflectionAction.AddStep(
                afterStep = (json["after_step"] as? Number)?.toInt() ?: 0,
                description = json["description"] as? String ?: "",
                kind = json["kind"] as? String,
                suggestedParams = json["suggested_params"] as? Map<String, Any> ?: emptyMap()
            )

            ActionType.SKIP_STEP -> ReflectionAction.SkipStep(
                step = (json["step"] as? Number)?.toInt() ?: 0,
                reason = json["reason"] as? String ?: ""
            )

            ActionType.MODIFY_STEP -> ReflectionAction.ModifyStep(
                step = (json["step"] as? Number)?.toInt() ?: 0,
                newDescription = json["new_description"] as? String,
                newParams = json["new_params"] as? Map<String, Any>
            )

            ActionType.RETRY_STEP -> ReflectionAction.RetryStep(
                step = (json["step"] as? Number)?.toInt() ?: 0,
                reason = json["reason"] as? String ?: ""
            )
        }
    }
}

/**
 * Reflection decision from LLM
 */
data class ReflectionDecision(
    val decision: DecisionType,
    val reasoning: String,
    val analysis: String,
    val actions: List<ReflectionAction>,
    val question: String? = null,
    val questionOptions: List<String>? = null
)

enum class DecisionType {
    CONTINUE,      // Continue with plan as-is
    MODIFY_PLAN,   // Modify plan (add/skip/modify steps)
    ASK_USER,      // Ask user for guidance
    ABORT          // Abort execution (unrecoverable error)
}

sealed class ReflectionAction {
    data class AddStep(
        val afterStep: Int,
        val description: String,
        val kind: String?,
        val suggestedParams: Map<String, Any>
    ) : ReflectionAction()

    data class SkipStep(
        val step: Int,
        val reason: String
    ) : ReflectionAction()

    data class ModifyStep(
        val step: Int,
        val newDescription: String?,
        val newParams: Map<String, Any>?
    ) : ReflectionAction()

    data class RetryStep(
        val step: Int,
        val reason: String
    ) : ReflectionAction()
}

enum class ActionType {
    ADD_STEP,
    SKIP_STEP,
    MODIFY_STEP,
    RETRY_STEP
}
