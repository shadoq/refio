package pl.jclab.refio.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.turn.TurnEventListener
import java.util.concurrent.ConcurrentHashMap

private val logger = dualLogger("AbstractToolCallLifecycleListener")

/**
 * Shared bookkeeping for the "temporary assistant message per tool call" pattern
 * used by both the IntelliJ plugin ([CoreSessionService]) and the CLI TUI.
 *
 * Maintains a `toolCallId → tempMessageId` map and dispatches lifecycle hooks
 * ([onCreateTempMessage], [onUpdateTempMessage], [onFinalizeTempMessage]) that
 * subclasses wire to their own message store (Core [pl.jclab.refio.api.models.Message]
 * for the plugin, `TuiChatMessage` for CLI).
 *
 * Subclasses must also implement [onAfterToolLifecycleEvent] if they need to
 * trigger DB reloads (e.g. `subtaskTracker.loadSubtasks()`).
 */
abstract class AbstractToolCallLifecycleListener(
    private val scope: CoroutineScope,
) : TurnEventListener {

    protected val toolCallMessageIds = ConcurrentHashMap<String, String>()

    /** Create a provisional "tool call in progress" message, returning its id. */
    protected abstract fun onCreateTempMessage(taskId: String, toolCall: ToolCallData): String

    /** Apply streaming content updates to the tool-call message identified by [messageId]. */
    protected abstract fun onUpdateTempMessage(
        messageId: String,
        toolCallId: String,
        delta: String,
        accumulated: String,
    )

    /**
     * Finalize the tool-call message with the execution result.
     * The implementation should mark it no-longer-streaming and record success/failure.
     */
    protected abstract fun onFinalizeTempMessage(
        messageId: String,
        toolCall: ToolCallData,
        result: String,
        success: Boolean,
    )

    /**
     * Optional hook for refreshing DB-backed views (e.g. subtask tracker) after any
     * tool lifecycle event. Default implementation: no-op.
     */
    protected open suspend fun onAfterToolLifecycleEvent(taskId: String) {}

    override fun onTurnStarted(
        taskId: String,
        mode: TaskMode,
        runId: String,
        parentRunId: String?,
        depth: Int,
    ) {
        logger.info {
            "[TURN_LOOP] Turn started: taskId=$taskId, mode=$mode, runId=$runId, " +
                "parentRunId=${parentRunId ?: "-"}, depth=$depth"
        }
    }

    override fun onToolExecutionStarted(taskId: String, toolCall: ToolCallData) {
        logger.info { "[TURN_LOOP] Tool started: ${toolCall.name}" }
        val tempId = onCreateTempMessage(taskId, toolCall)
        toolCallMessageIds[toolCall.id] = tempId
        scope.launch { onAfterToolLifecycleEvent(taskId) }
    }

    override fun onToolStreamChunk(
        taskId: String,
        toolCallId: String,
        delta: String,
        accumulated: String,
    ) {
        val messageId = toolCallMessageIds[toolCallId] ?: run {
            logger.warn { "[TURN_LOOP] Tool stream chunk for unknown tool call: $toolCallId" }
            return
        }
        onUpdateTempMessage(messageId, toolCallId, delta, accumulated)
    }

    override fun onToolExecutionCompleted(
        taskId: String,
        toolCall: ToolCallData,
        result: String,
        success: Boolean,
    ) {
        logger.info { "[TURN_LOOP] Tool completed: ${toolCall.name}, success=$success" }
        val messageId = toolCallMessageIds.remove(toolCall.id) ?: return
        onFinalizeTempMessage(messageId, toolCall, result, success)
        scope.launch { onAfterToolLifecycleEvent(taskId) }
    }

    override fun onStreamChunk(taskId: String, delta: String, accumulated: String) {
        // Callers route regular LLM content through their own stream callback.
    }

    override fun onTurnCompleted(
        taskId: String,
        result: TurnResult,
        runId: String,
        parentRunId: String?,
        depth: Int,
    ) {
        logger.info {
            "[TURN_LOOP] Turn completed: taskId=$taskId, success=${result.success}, " +
                "iterations=${result.iterations}, runId=$runId, parentRunId=${parentRunId ?: "-"}, depth=$depth"
        }
    }

    /** Clear the bookkeeping map — call after [MessageDispatcher.loadMessages] reconciles against DB. */
    fun clearTracking() {
        toolCallMessageIds.clear()
    }
}
