package pl.jclab.refio.core.workflow

import kotlinx.coroutines.CancellationException
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.core.services.orchestration.UserInteraction
import pl.jclab.refio.core.services.execution.unified.ExecutionEventListener
import pl.jclab.refio.core.workflow.executors.ChatExecutor
import pl.jclab.refio.core.workflow.executors.PlanExecutor
import pl.jclab.refio.core.workflow.executors.StepExecutor
import pl.jclab.refio.core.workflow.executors.SubagentExecutor
import pl.jclab.refio.core.workflow.executors.SingleToolExecutor
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.WorkflowIntent
import pl.jclab.refio.core.workflow.models.WorkflowRequest
import pl.jclab.refio.services.logging.dualLogger

private val logger = dualLogger("WorkflowOrchestrator")

/**
 * Orchestrates workflow intent routing and execution using adapter executors.
 */
class WorkflowOrchestrator(
    private val intentRouter: IntentRouter,
    private val chatExecutor: ChatExecutor,
    private val planExecutor: PlanExecutor,
    private val stepExecutor: StepExecutor,
    private val subagentExecutor: SubagentExecutor?,
    private val userInteraction: UserInteraction? = null,
    private val singleToolExecutor: SingleToolExecutor? = null
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
            "stream=$stream, orchestration=${uiState.orchestrationEnabled}"
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
                        val response = chatExecutor.execute(intent, stream, onChunk)
                        val output = (response as IntentResult.ChatResult).response.output
                        logger.info { "[WORKFLOW] Chat execution complete: outputChars=${output.length} (taskId=$taskLabel)" }
                        listener.onStreamComplete(output)
                        response
                    }

                    is WorkflowIntent.Plan -> {
                        listener.onPlanningStarted()
                        val onChunk = if (stream) streamCallback(listener) else null
                        logger.info { "[WORKFLOW] Plan execution start: stream=$stream (taskId=$taskLabel)" }
                        val response = planExecutor.execute(intent, stream, onChunk)
                        val plan = (response as IntentResult.PlanResult).response.plan
                        logger.info { "[WORKFLOW] Plan execution complete: planChars=${plan.length} (taskId=$taskLabel)" }
                        listener.onStreamComplete(plan)
                        response
                    }

                    is WorkflowIntent.ExecuteStep -> {
                        listener.onStepStarted(intent.subtaskId)
                        logger.info { "[WORKFLOW] Step execution start: subtaskId=${intent.subtaskId} (taskId=$taskLabel)" }
                        val response = stepExecutor.execute(
                            intent = intent,
                            listener = executionListener(listener),
                            orchestrationEnabled = uiState.orchestrationEnabled
                        )
                        executedStep = true
                        logger.info { "[WORKFLOW] Step execution complete: subtaskId=${intent.subtaskId} (taskId=$taskLabel)" }
                        response
                    }

                    is WorkflowIntent.Subagent -> {
                        val executor = subagentExecutor
                            ?: throw IllegalStateException("Subagent execution not available")
                        listener.onSubagentStarted(intent.name)
                        val onChunk = if (stream) streamCallback(listener) else null
                        logger.info { "[WORKFLOW] Subagent execution start: name=${intent.name}, stream=$stream (taskId=$taskLabel)" }
                        val response = executor.execute(intent, stream, onChunk)
                        val output = (response as IntentResult.SubagentResult).response.response
                        logger.info {
                            "[WORKFLOW] Subagent execution complete: name=${intent.name}, outputChars=${output.length} (taskId=$taskLabel)"
                        }
                        listener.onStreamComplete(output)
                        response
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

                    is WorkflowIntent.AskClarification -> {
                        val interaction = userInteraction
                            ?: throw IllegalStateException("UserInteraction not configured")
                        logger.info {
                            "[WORKFLOW] Asking for clarification: ${intent.question} (taskId=$taskLabel)"
                        }
                        val questionId = interaction.askQuestion(
                            taskId = intent.taskId,
                            question = intent.question,
                            options = intent.options.takeIf { it.isNotEmpty() }
                        )
                        logger.info { "[WORKFLOW] Clarification question posted: questionId=$questionId" }
                        IntentResult.ClarificationResult(
                            taskId = intent.taskId,
                            questionId = questionId,
                            question = intent.question,
                            options = intent.options
                        )
                    }

                    is WorkflowIntent.ExecuteTool -> {
                        val executor = singleToolExecutor
                            ?: throw IllegalStateException("SingleToolExecutor not configured for single tool execution")
                        logger.info {
                            "[WORKFLOW] Single tool execution: ${intent.toolName} (taskId=$taskLabel)"
                        }
                        val response = executor.execute(intent)
                        logger.info {
                            "[WORKFLOW] Tool execution complete: ${intent.toolName}, success=${response.success} (taskId=$taskLabel)"
                        }
                        listener.onStreamComplete(response.output)
                        response
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

            override fun onReflectionStream(step: pl.jclab.refio.core.db.Subtask, streamContent: String, isFinal: Boolean) {
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
            is WorkflowIntent.AskClarification -> OperationInfo.Idle
            is WorkflowIntent.ExecuteTool -> OperationInfo.ExecutingStep(0, 0, "Executing ${intent.toolName}")
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
            is WorkflowIntent.AskClarification -> "AskClarification(question=${intent.question.take(50)}...)"
            is WorkflowIntent.ExecuteTool -> "ExecuteTool(tool=${intent.toolName})"
        }
    }

    private object NoOpWorkflowListener : WorkflowEventListener
}
