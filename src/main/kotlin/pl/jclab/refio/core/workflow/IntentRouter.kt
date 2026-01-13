package pl.jclab.refio.core.workflow

import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.workflow.models.UIState
import pl.jclab.refio.core.workflow.models.WorkflowIntent
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("IntentRouter")

/**
 * Resolves workflow intent using fast paths and optional LLM-based classification.
 *
 * Priority order:
 * 1. Subagent invocation (!subagent-name pattern) - always fast path
 * 2. Pending subtasks in AUTO mode - continuation fast path
 * 3. LLM classification (when enabled) - analyzes ANY mode including CHAT
 * 4. Mode-based fallback (CHAT → Chat, PLAN/AGENT → Plan)
 *
 * LLM classification (when enabled via config):
 * - Works for ALL modes (CHAT, PLAN, AGENT)
 * - Uses IntentClassificationService to analyze input
 * - Maps classification to: Chat, Plan, AskClarification, or ExecuteTool
 *
 * See docs/features/0017-new-workflow.md for full specification.
 */
class IntentRouter(
    private val subtaskRepository: SubtaskRepository,
    private val subagentRouter: SubagentRouter?,
    private val classificationService: IntentClassificationService? = null
) {
    /**
     * Determine workflow intent for given UI state.
     *
     * @param uiState Current UI state snapshot
     * @param projectAnalysis Optional project context for LLM classification
     * @param listener Optional listener for UI updates during LLM classification
     * @return Resolved workflow intent
     */
    suspend fun determineIntent(
        uiState: UIState,
        projectAnalysis: String? = null,
        listener: WorkflowEventListener? = null
    ): WorkflowIntent {
        val input = uiState.input

        // Fast path 1: Subagent invocation (explicit user intent - always fast path)
        val subagentInvocation = subagentRouter?.parseSubagentInvocation(input)
        if (subagentInvocation != null) {
            val (name, prompt) = subagentInvocation
            logger.debug { "[INTENT_ROUTER] Fast path: Subagent invocation detected - $name" }
            return WorkflowIntent.Subagent(
                taskId = uiState.taskId ?: "",
                name = name,
                prompt = prompt,
                contextRefs = uiState.contextRefs,
                model = uiState.model,
                provider = uiState.provider
            )
        }

        // Fast path 2: Pending subtasks in AUTO mode (continuation - always fast path)
        if (uiState.taskId != null && uiState.executionMode == pl.jclab.refio.api.models.ExecutionMode.AUTO) {
            val pendingSubtasks = subtaskRepository.findByTaskId(uiState.taskId)
                .filter { it.status in listOf(TaskStatus.PENDING, TaskStatus.PLANNED) }

            if (pendingSubtasks.isNotEmpty()) {
                val nextSubtask = pendingSubtasks.minByOrNull { it.orderIndex }
                    ?: throw IllegalStateException("Pending subtask list is empty")
                logger.debug { "[INTENT_ROUTER] Fast path: Pending subtask found - ${nextSubtask.id}" }
                return WorkflowIntent.ExecuteStep(
                    taskId = uiState.taskId,
                    subtaskId = nextSubtask.id
                )
            }
        }

        // LLM classification path - works for ALL modes (CHAT, PLAN, AGENT)
        // Checked dynamically from config to allow runtime toggle
        val llmEnabled = uiState.intentClassificationEnabled
        val hasService = classificationService != null
        val hasAnalysis = projectAnalysis != null

        logger.info {
            "[INTENT_ROUTER] LLM classification check: enabled=$llmEnabled, hasService=$hasService, hasAnalysis=$hasAnalysis (mode=${uiState.mode})"
        }

        if (llmEnabled && hasService && projectAnalysis != null) {
            logger.info { "[INTENT_ROUTER] Using LLM classification for intent routing (mode: ${uiState.mode})" }
            return classifyWithLlm(uiState, projectAnalysis, listener)
        }

        // Mode-based fallback (when LLM classification disabled or not available)
        return when (uiState.mode) {
            TaskMode.CHAT -> {
                logger.info { "[INTENT_ROUTER] Fallback: CHAT mode -> Chat intent" }
                WorkflowIntent.Chat(
                    taskId = uiState.taskId ?: "",
                    input = input,
                    contextRefs = uiState.contextRefs,
                    model = uiState.model,
                    provider = uiState.provider
                )
            }
            TaskMode.PLAN, TaskMode.AGENT -> {
                logger.info { "[INTENT_ROUTER] Fallback: ${uiState.mode} mode -> Plan intent" }
                WorkflowIntent.Plan(
                    taskId = uiState.taskId ?: "",
                    input = input,
                    contextRefs = uiState.contextRefs,
                    model = uiState.model,
                    provider = uiState.provider,
                    interactive = uiState.executionMode == pl.jclab.refio.api.models.ExecutionMode.INTERACTIVE
                )
            }
        }
    }

    /**
     * Classify intent using LLM and map to WorkflowIntent.
     */
    private suspend fun classifyWithLlm(
        uiState: UIState,
        projectAnalysis: String,
        listener: WorkflowEventListener?
    ): WorkflowIntent {
        val modelLabel = listOfNotNull(uiState.provider, uiState.model).joinToString("/").ifBlank { "auto" }

        // Show operation status in UI (GlobalMetrics for status bar)
        GlobalMetrics.setCurrentOperation(
            OperationInfo.IntentClassification(
                model = modelLabel,
                mode = uiState.mode.name
            )
        )

        // Notify listener (for ChatView message)
        listener?.onIntentClassificationStarted(modelLabel, uiState.mode.name)

        val classification = try {
            classificationService!!.classifyIntent(
                taskMode = uiState.mode,
                userInput = uiState.input,
                projectAnalysis = projectAnalysis,
                taskId = uiState.taskId,
                model = uiState.model,
                provider = uiState.provider
            )
        } finally {
            // Clear operation status - next operation will set its own
            // (Don't set Idle here, WorkflowOrchestrator will set the resolved intent operation)
        }

        // Notify listener with result
        listener?.onIntentClassificationResult(classification)

        logger.info { "[INTENT_ROUTER] LLM classification result: ${classification::class.simpleName} (reasoning: ${classification.reasoning})" }

        return when (classification) {
            is IntentClassificationResult.ChatResponse -> {
                logger.debug { "[INTENT_ROUTER] LLM decision: CHAT_RESPONSE" }
                WorkflowIntent.Chat(
                    taskId = uiState.taskId ?: "",
                    input = uiState.input,
                    contextRefs = uiState.contextRefs,
                    model = uiState.model,
                    provider = uiState.provider
                )
            }

            is IntentClassificationResult.ClarificationNeeded -> {
                logger.debug { "[INTENT_ROUTER] LLM decision: CLARIFICATION_NEEDED - ${classification.question}" }
                WorkflowIntent.AskClarification(
                    taskId = uiState.taskId ?: "",
                    question = classification.question,
                    options = classification.options,
                    reasoning = classification.reasoning
                )
            }

            is IntentClassificationResult.SingleTool -> {
                logger.debug { "[INTENT_ROUTER] LLM decision: SINGLE_TOOL - ${classification.toolName}" }
                WorkflowIntent.ExecuteTool(
                    taskId = uiState.taskId ?: "",
                    toolName = classification.toolName,
                    toolArgs = classification.toolArgs,
                    reasoning = classification.reasoning,
                    contextRefs = uiState.contextRefs
                )
            }

            is IntentClassificationResult.MultiStepPlan -> {
                logger.debug { "[INTENT_ROUTER] LLM decision: MULTI_STEP_PLAN" }
                WorkflowIntent.Plan(
                    taskId = uiState.taskId ?: "",
                    input = uiState.input,
                    contextRefs = uiState.contextRefs,
                    model = uiState.model,
                    provider = uiState.provider,
                    interactive = uiState.executionMode == pl.jclab.refio.api.models.ExecutionMode.INTERACTIVE
                )
            }
        }
    }
}
