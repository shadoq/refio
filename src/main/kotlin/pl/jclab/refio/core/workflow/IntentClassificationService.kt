package pl.jclab.refio.core.workflow

import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.core.db.PromptType
import pl.jclab.refio.core.llm.JsonExtractor
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("IntentClassificationService")

/**
 * Service for LLM-based intent classification.
 *
 * Analyzes user input and determines the best course of action:
 * - CHAT_RESPONSE: Direct answer without tools
 * - CLARIFICATION_NEEDED: Ask user for more information
 * - SINGLE_TOOL: Execute one tool directly
 * - MULTI_STEP_PLAN: Create execution plan
 *
 * See docs/features/0017-new-workflow.md for full specification.
 */
class IntentClassificationService(
    private val promptsService: PromptsService,
    private val llmClient: LLMClient,
    private val toolDescriptionBuilder: ToolDescriptionBuilder,
    private val configService: ConfigService
) {
    companion object {
        private const val SOURCE = "IntentClassifier"
    }

    /**
     * Classify user input to determine the appropriate action.
     *
     * @param taskMode Current task mode (PLAN or AGENT)
     * @param userInput The user's input text
     * @param projectAnalysis Brief project context summary
     * @param taskId Optional task ID for logging
     * @param model LLM model to use (optional, uses default if not specified)
     * @param provider LLM provider to use (optional, uses default if not specified)
     * @return Classification result with decision and metadata
     */
    suspend fun classifyIntent(
        taskMode: TaskMode,
        userInput: String,
        projectAnalysis: String,
        taskId: String? = null,
        model: String? = null,
        provider: String? = null
    ): IntentClassificationResult {
        logger.info { "[INTENT_CLASSIFIER] Classifying intent for input: ${userInput.take(100)}..." }

        // Build tool descriptions based on task mode
        val toolDescriptions = toolDescriptionBuilder.getToolDescriptions(
            mode = pl.jclab.refio.core.db.TaskMode.valueOf(taskMode.name),
            taskId = taskId
        )

        // Get system prompt with variable substitution
        val systemPrompt = promptsService.getSystemPrompt(
            type = PromptType.SYSTEM_INTENT_CLASSIFIER,
            variables = mapOf(
                "task_mode" to taskMode.name,
                "tool_descriptions" to toolDescriptions,
                "project_analysis" to projectAnalysis,
                "user_input" to userInput
            )
        )

        // Resolve model and provider
        val resolvedModel = model ?: configService.get("default_model.chat") ?: "gpt-4o-mini"
        val resolvedProvider = provider ?: llmClient.inferProvider(resolvedModel)

        logger.debug { "[INTENT_CLASSIFIER] Using model: $resolvedProvider/$resolvedModel" }

        // Call LLM
        val response = llmClient.complete(
            provider = resolvedProvider,
            model = resolvedModel,
            messages = listOf(LLMMessage(role = "user", content = "Classify the following user input.")),
            systemPrompt = systemPrompt,
            maxTokens = 1024,
            temperature = 0.3,
            taskId = taskId,
            source = SOURCE
        )

        logger.debug { "[INTENT_CLASSIFIER] LLM response: ${response.content}" }

        // Parse JSON response
        return parseClassificationResponse(response.content)
    }

    /**
     * Parse LLM response JSON into IntentClassificationResult.
     */
    private fun parseClassificationResponse(content: String): IntentClassificationResult {
        val json = try {
            JsonExtractor.extractAndParse(content)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to extract JSON from classification response: $content", e)
        }

        val decision = json["decision"]?.toString()
            ?: throw IllegalStateException("Missing 'decision' field in classification response")

        val reasoning = json["reasoning"]?.toString() ?: "No reasoning provided"

        logger.info { "[INTENT_CLASSIFIER] Decision: $decision, Reasoning: $reasoning" }

        return when (decision.toString().uppercase()) {
            "CHAT_RESPONSE" -> IntentClassificationResult.ChatResponse(reasoning)

            "CLARIFICATION_NEEDED" -> {
                val question = json["question"]?.toString()
                    ?: throw IllegalStateException("CLARIFICATION_NEEDED requires 'question' field")

                @Suppress("UNCHECKED_CAST")
                val options = (json["question_options"] as? List<String>) ?: emptyList()

                IntentClassificationResult.ClarificationNeeded(reasoning, question, options)
            }

            "SINGLE_TOOL" -> {
                val toolName = json["tool_name"]?.toString()
                    ?: throw IllegalStateException("SINGLE_TOOL requires 'tool_name' field")

                @Suppress("UNCHECKED_CAST")
                val toolArgs = (json["tool_args"] as? Map<String, Any>) ?: emptyMap()

                IntentClassificationResult.SingleTool(reasoning, toolName, toolArgs)
            }

            "MULTI_STEP_PLAN" -> IntentClassificationResult.MultiStepPlan(reasoning)

            else -> {
                logger.warn { "[INTENT_CLASSIFIER] Unknown decision: $decision, defaulting to MULTI_STEP_PLAN" }
                IntentClassificationResult.MultiStepPlan(reasoning)
            }
        }
    }
}
