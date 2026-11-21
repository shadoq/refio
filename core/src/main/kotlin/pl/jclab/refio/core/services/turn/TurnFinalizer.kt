package pl.jclab.refio.core.services.turn

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.debug.TurnStopReason
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.core.utils.GsonInstance

private val logger = dualLogger("TurnFinalizer")

/**
 * Handles turn completion and cleanup.
 *
 * Responsibilities:
 * - Persist assistant message if needed
 * - Update metrics to idle state
 * - Notify listeners of completion
 */
class TurnFinalizer(
    private val chatMessageRepository: ChatMessageRepository
) {
    /**
     * Complete a turn with optional message persistence and listener notification.
     *
     * @param listener Optional listener with onTurnCompleted method
     */
    fun completeTurn(
        taskId: String,
        result: TurnResult,
        listener: TurnCompletionListener?,
        runId: String,
        parentRunId: String?,
        depth: Int,
        persistAssistantMessage: Boolean,
        metadata: String? = null,
        agentInstanceId: String? = null,
        agentName: String? = null,
        agentDepth: Int? = null,
        /** Why the turn ended; only [TurnStopReason.OUTPUT_TRUNCATED] leaves a mark on the stored message. */
        stopReason: TurnStopReason? = null,
    ): TurnResult {
        if (persistAssistantMessage) {
            val content = result.response.ifBlank {
                if (result.success) "Task completed." else "Task finished without a final response."
            }
            chatMessageRepository.create(
                taskId = taskId,
                role = MessageRole.ASSISTANT,
                content = content,
                metadata = withStopReason(metadata, stopReason),
                agentInstanceId = agentInstanceId,
                agentName = agentName,
                agentDepth = agentDepth,
            )
        }

        GlobalMetrics.setCurrentOperation(OperationInfo.Idle)
        listener?.onTurnCompleted(taskId, result, runId, parentRunId, depth)
        return result
    }

    /**
     * Marks a reply that the output cap cut off, so a UI reading the stored message can tell it
     * from a finished answer. Only that reason is recorded: every other exit stores exactly the
     * metadata it was given. Existing keys stay; metadata that is not a JSON object is kept as is
     * rather than overwritten, since dropping it would lose more than the mark adds.
     */
    private fun withStopReason(metadata: String?, stopReason: TurnStopReason?): String? {
        if (stopReason != TurnStopReason.OUTPUT_TRUNCATED) {
            return metadata
        }
        val json = if (metadata.isNullOrBlank()) {
            JsonObject()
        } else {
            runCatching { JsonParser.parseString(metadata) }.getOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
        }
        if (json == null) {
            logger.warn { "Assistant message metadata is not a JSON object, stop reason not recorded" }
            return metadata
        }
        json.addProperty(STOP_REASON_KEY, TurnStopReason.OUTPUT_TRUNCATED.name)
        return GsonInstance.gson.toJson(json)
    }

    companion object {
        /** Metadata key of the stored assistant message that names why its turn stopped. */
        const val STOP_REASON_KEY = "stop_reason"
    }
}

/**
 * Minimal interface for turn completion notifications.
 */
interface TurnCompletionListener {
    fun onTurnCompleted(
        taskId: String,
        result: TurnResult,
        runId: String,
        parentRunId: String?,
        depth: Int
    ) {}
}
