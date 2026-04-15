package pl.jclab.refio.core.workflow

import kotlinx.coroutines.CancellationException
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.core.api.PlanningRequest
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.routers.AgentRouter
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.models.api.ChatRequest
import pl.jclab.refio.core.models.api.LLMParams
import pl.jclab.refio.core.services.ChatService
import pl.jclab.refio.core.services.PlanningService
import pl.jclab.refio.core.services.execution.unified.ExecutionEventListener
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.core.services.orchestration.UserInteraction
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.WorkflowIntent
import pl.jclab.refio.core.workflow.models.WorkflowRequest
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("WorkflowOrchestrator")

/**
 * Orchestrates workflow intent routing and execution.
 *
 * Dispatches directly to domain services (ChatService/PlanningService/AgentRouter/SubagentRouter) —
 * no adapter executors. AUTO mode loops through intent resolution until a non-step intent resolves.
 */
class WorkflowOrchestrator(
    private val intentRouter: IntentRouter,
    private val chatService: ChatService,
    private val planningService: PlanningService,
    private val agentRouter: AgentRouter,
    private val subagentRouter: SubagentRouter?,
    private val userInteraction: UserInteraction? = null
) {
    suspend fun execute(
        request: WorkflowRequest,
        listener: WorkflowEventListener = NoOpWorkflowListener
    ): IntentResult {
        val uiState = request.uiState
        val stream = uiState.streamingEnabled
        val taskLabel = uiState.taskId ?: "none"

        logger.info {
            "[WORKFLOW] Start: taskId=$taskLabel, mode=${uiState.mode}, executionMode=${uiState.executionMode}, " +
            "stream=$stream"
        }

        var lastResult: IntentResult? = null
        var executedStep = false

        try {
            while (true) {
                if (GlobalMetrics.isCancelled()) {
                    logger.warn { "[WORKFLOW] Cancelled by user: taskId=$taskLabel" }
                    throw CancellationException("Operation cancelled by user")
                }

                listener.onDecisionPhase()
                val intent = intentRouter.determineIntent(uiState, request.projectAnalysis, listener)
                setOperation(intent, uiState)
                logger.info { "[WORKFLOW] Intent resolved: ${intentSummary(intent)} (taskId=$taskLabel)" }

                if (executedStep && uiState.executionMode == ExecutionMode.AUTO && intent is WorkflowIntent.Plan) {
                    logger.info { "[WORKFLOW] AUTO mode: executed step already, skipping plan (taskId=$taskLabel)" }
                    break
                }

                listener.onExecutionPhase(intent)

                val result = when (intent) {
                    is WorkflowIntent.Chat -> {
                        listener.onChatStarted()
                        val onChunk = if (stream) streamCallback(listener) else null
                        logger.info { "[WORKFLOW] Chat execution start: stream=$stream (taskId=$taskLabel)" }
                        val chatRequest = ChatRequest(
                            taskId = intent.taskId,
                            mode = TaskMode.CHAT,
                            input = intent.input,
                            contextRefs = intent.contextRefs,
                            params = LLMParams(
                                model = intent.model,
                                provider = intent.provider
                            )
                        )
                        val response = chatService.chat(chatRequest, stream, onChunk)
                        logger.info { "[WORKFLOW] Chat execution complete: outputChars=${response.output.length} (taskId=$taskLabel)" }
                        listener.onStreamComplete(response.output)
                        IntentResult.ChatResult(response)
                    }

                    is WorkflowIntent.Plan -> {
                        listener.onPlanningStarted()
                        val onChunk = if (stream) streamCallback(listener) else null
                        logger.info { "[WORKFLOW] Plan execution start: stream=$stream (taskId=$taskLabel)" }
                        val planningRequest = PlanningRequest(
                            input = intent.input,
                            contextRefs = intent.contextRefs,
                            model = intent.model,
                            provider = intent.provider,
                            interactive = intent.interactive
                        )
                        val response = planningService.createPlan(intent.taskId, planningRequest, stream, onChunk)
                        logger.info { "[WORKFLOW] Plan execution complete: planChars=${response.plan.length} (taskId=$taskLabel)" }
                        listener.onStreamComplete(response.plan)
                        IntentResult.PlanResult(response)
                    }

                    is WorkflowIntent.ExecuteStep -> {
                        listener.onStepStarted(intent.subtaskId)
                        logger.info { "[WORKFLOW] Step execution start: subtaskId=${intent.subtaskId} (taskId=$taskLabel)" }
                        val execListener = executionListener(listener)
                        // Two paths kept intentionally — StepExecutor previously branched on listener == null.
                        val response = agentRouter.executeSubtaskStepWithListener(
                            intent.taskId,
                            intent.subtaskId,
                            execListener
                        )
                        executedStep = true
                        logger.info { "[WORKFLOW] Step execution complete: subtaskId=${intent.subtaskId} (taskId=$taskLabel)" }
                        IntentResult.StepResult(response)
                    }

                    is WorkflowIntent.Subagent -> {
                        val router = subagentRouter
                            ?: throw IllegalStateException("Subagent execution not available")
                        listener.onSubagentStarted(intent.name)
                        val onChunk = if (stream) streamCallback(listener) else null
                        logger.info { "[WORKFLOW] Subagent execution start: name=${intent.name}, stream=$stream (taskId=$taskLabel)" }
                        // parentModel only when both provider and model are set — lifted verbatim from SubagentExecutor.
                        val parentModel = intent.model?.let { model ->
                            intent.provider?.let { provider -> "$provider/$model" }
                        }
                        val response = router.invoke(
                            taskId = intent.taskId,
                            name = intent.name,
                            prompt = intent.prompt,
                            contextRefs = intent.contextRefs,
                            stream = stream,
                            onChunk = onChunk,
                            parentModel = parentModel
                        )
                        logger.info {
                            "[WORKFLOW] Subagent execution complete: name=${intent.name}, outputChars=${response.response.length} (taskId=$taskLabel)"
                        }
                        listener.onStreamComplete(response.response)
                        IntentResult.SubagentResult(response)
                    }

                    is WorkflowIntent.AnswerQuestion -> {
                        val interaction = userInteraction
                            ?: throw IllegalStateException("UserInteraction not configured")
                        logger.info {
                            "[WORKFLOW] Answering question: questionId=${intent.questionId} (taskId=$taskLabel)"
                        }
                        interaction.provideResponse(intent.questionId, intent.answer)
                        IntentResult.AnswerResult(intent.taskId)
                    }
                }

                lastResult = result
                listener.onIntentCompleted(intent, result)

                if (intent !is WorkflowIntent.ExecuteStep || uiState.executionMode != ExecutionMode.AUTO) {
                    logger.info { "[WORKFLOW] Execution complete for intent: ${intentSummary(intent)} (taskId=$taskLabel)" }
                    break
                }
            }
        } catch (e: CancellationException) {
            logger.warn { "[WORKFLOW] Cancelled: ${e.message} (taskId=$taskLabel)" }
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[WORKFLOW] Execution failed (taskId=$taskLabel): ${e.message}" }
            throw e
        } finally {
            GlobalMetrics.setCurrentOperation(OperationInfo.Idle)
        }

        val finalResult = lastResult ?: throw IllegalStateException("Workflow produced no result")
        listener.onWorkflowComplete(finalResult)
        logger.info { "[WORKFLOW] Workflow complete: taskId=$taskLabel, result=${finalResult::class.simpleName}" }
        return finalResult
    }

    private fun streamCallback(listener: WorkflowEventListener): StreamCallback? {
        return { chunk -> listener.onStreamChunk(chunk.accumulated) }
    }

    private fun executionListener(listener: WorkflowEventListener): ExecutionEventListener {
        return object : ExecutionEventListener {
            override fun onStepPlanningStream(step: pl.jclab.refio.core.db.Subtask, streamContent: String, isComplete: Boolean) {
                listener.onStreamChunk(streamContent)
            }

            override fun onToolCodeGenerationStream(
                step: pl.jclab.refio.core.db.Subtask,
                toolName: String,
                filePath: String,
                streamContent: String,
                isComplete: Boolean
            ) {
                listener.onStreamChunk(streamContent)
            }

            override fun onReflectionStream(step: pl.jclab.refio.core.db.Subtask, streamContent: String, isComplete: Boolean) {
                listener.onStreamChunk(streamContent)
            }
        }
    }

    private fun setOperation(intent: WorkflowIntent, uiState: pl.jclab.refio.core.workflow.models.UIState) {
        val modelLabel = listOfNotNull(uiState.provider, uiState.model).joinToString("/").ifBlank { "auto" }
        val operation = when (intent) {
            is WorkflowIntent.Chat -> OperationInfo.ChatRequest(modelLabel)
            is WorkflowIntent.Plan -> OperationInfo.PlanningRequest(modelLabel)
            is WorkflowIntent.ExecuteStep -> OperationInfo.ExecutingStep(0, 0, "Executing step")
            is WorkflowIntent.Subagent -> OperationInfo.SubagentRequest(intent.name)
            is WorkflowIntent.AnswerQuestion -> OperationInfo.Idle
        }
        GlobalMetrics.setCurrentOperation(operation)
        logger.debug { "[WORKFLOW] Operation set: ${operation::class.simpleName} ($modelLabel)" }
    }

    private fun intentSummary(intent: WorkflowIntent): String {
        return when (intent) {
            is WorkflowIntent.Chat -> "Chat"
            is WorkflowIntent.Plan -> "Plan"
            is WorkflowIntent.ExecuteStep -> "ExecuteStep(subtaskId=${intent.subtaskId})"
            is WorkflowIntent.Subagent -> "Subagent(name=${intent.name})"
            is WorkflowIntent.AnswerQuestion -> "AnswerQuestion(questionId=${intent.questionId})"
        }
    }

    private object NoOpWorkflowListener : WorkflowEventListener
}
