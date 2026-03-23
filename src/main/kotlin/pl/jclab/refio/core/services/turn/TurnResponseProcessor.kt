package pl.jclab.refio.core.services.turn

import kotlinx.serialization.json.Json
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.llm.JsonExtractor
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("TurnResponseProcessor")

/**
 * Processes LLM responses for turn execution.
 *
 * Responsibilities:
 * - Extract assistant thinking from response
 * - Create legacy plan subtasks (for backwards compatibility)
 * - Handle response metadata
 */
class TurnResponseProcessor(
    private val subtaskRepository: SubtaskRepository,
    private val toolRegistry: ToolRegistry,
    private val toolDescriptionBuilder: ToolDescriptionBuilder
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /**
     * Extract assistant thinking from LLM response.
     *
     * First checks native thinking field, then attempts to extract from JSON.
     */
    fun resolveAssistantThinking(response: LLMResponse): String? {
        val nativeThinking = response.thinking?.takeIf { it.isNotBlank() }
        if (nativeThinking != null) return nativeThinking

        return try {
            val content = response.content
            val trimmed = content.trim()
            if (!trimmed.startsWith("{")) return null

            val jsonElement = json.parseToJsonElement(trimmed)
            val obj = jsonElement as? kotlinx.serialization.json.JsonObject ?: return null
            val thinkingField = obj["thinking"] as? kotlinx.serialization.json.JsonPrimitive
            thinkingField?.content?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Legacy function to create plan subtasks from JSON response.
     *
     * NOTE: As of 0021-plan-fix, PLAN mode now uses the same "actions" format as AGENT
     * and executes tools directly. This function is kept for backwards compatibility
     * in case the model returns the old "subtasks" format in its final response.
     *
     * These subtasks are created as PLANNED status for UI display only - they are NOT executed.
     *
     * @return Number of subtasks created
     */
    fun tryCreatePlanSubtasks(
        taskId: String,
        mode: TaskMode,
        executionMode: ExecutionMode,
        llmResponse: LLMResponse
    ): Int {
        if (mode != TaskMode.PLAN) return 0

        val planData = try {
            JsonExtractor.extractAndParsePlanningResponse(llmResponse.content)
        } catch (e: Exception) {
            logger.debug { "[PLAN] No valid plan JSON to convert: ${e.message}" }
            return 0
        }

        val subtasks = planData["subtasks"] as? List<*> ?: return 0
        if (subtasks.isEmpty()) return 0

        val validToolNames = toolDescriptionBuilder.getValidToolNames(mode, taskId)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        val maxOrderIndex = subtaskRepository.getMaxOrderIndex(taskId) ?: 0
        var created = 0

        subtasks.forEachIndexed { index, step ->
            val stepMap = step as? Map<*, *> ?: return@forEachIndexed
            val toolName = stepMap["kind"]?.toString() ?: "plan_step"
            val description = stepMap["description"]?.toString()
                ?: stepMap["name"]?.toString()
                ?: "Plan step ${index + 1}"
            val rawArgs = stepMap["tool_args"] as? Map<*, *> ?: emptyMap<Any, Any>()

            if (toolName != "plan_step" && toolName !in validToolNames) {
                logger.warn { "[PLAN] Skipping step with invalid tool '$toolName' (not in available tools)" }
                return@forEachIndexed
            }

            val toolArgs = rawArgs.mapKeys { it.key.toString() }
            val planStructure = mapOf(
                "intent" to description,
                "tool_type" to toolName,
                "suggested_params" to toolArgs,
                "description" to description
            )

            subtaskRepository.create(
                taskId = taskId,
                orderIndex = maxOrderIndex + index + 1,
                kind = toolRegistry.toSubtaskKind(toolName),
                description = description,
                paramsJson = gson.toJson(planStructure),
                requiresApproval = executionMode == ExecutionMode.INTERACTIVE,
                status = TaskStatus.PLANNED,
                llmModel = llmResponse.model,
                llmProvider = llmResponse.provider
            )
            created++
        }

        if (created > 0) {
            logger.info { "[PLAN] Created $created plan subtasks" }
        }
        return created
    }
}
