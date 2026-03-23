package pl.jclab.refio.core.workflow

import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.workflow.models.UIState
import pl.jclab.refio.core.workflow.models.WorkflowIntent
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("IntentRouter")

/**
 * Resolves workflow intent using fast paths and mode-based routing.
 *
 * Priority order:
 * 1. Subagent invocation (!subagent-name pattern) - always fast path
 * 2. Pending subtasks in AUTO mode - continuation fast path
 * 3. Mode-based fallback (CHAT → Chat, PLAN/AGENT → Plan)
 */
class IntentRouter(
    private val subtaskRepository: SubtaskRepository,
    private val subagentRouter: SubagentRouter?
) {
    /**
     * Determine workflow intent for given UI state.
     *
     * @param uiState Current UI state snapshot
     * @param projectAnalysis Optional project context
     * @param listener Optional listener
     * @return Resolved workflow intent
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun determineIntent(
        uiState: UIState,
        _projectAnalysis: String? = null,
        _listener: WorkflowEventListener? = null
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

        // Mode-based routing
        return when (uiState.mode) {
            TaskMode.CHAT -> {
                logger.info { "[INTENT_ROUTER] CHAT mode -> Chat intent" }
                WorkflowIntent.Chat(
                    taskId = uiState.taskId ?: "",
                    input = input,
                    contextRefs = uiState.contextRefs,
                    model = uiState.model,
                    provider = uiState.provider
                )
            }
            TaskMode.PLAN, TaskMode.AGENT -> {
                logger.info { "[INTENT_ROUTER] ${uiState.mode} mode -> Plan intent" }
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

}

