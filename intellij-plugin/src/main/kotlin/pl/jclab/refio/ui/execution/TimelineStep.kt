package pl.jclab.refio.ui.execution

import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.ToolCallStatus
import pl.jclab.refio.ui.components.chat.toolcall.ToolCallRowView

/**
 * One row of the chat timeline: a tool call the turn made, reduced to what fits 22 px.
 *
 * The timeline is a navigation aid, not a second transcript - [messageId] is what makes a row
 * clickable, because it addresses the very bubble the transcript already rendered.
 */
data class TimelineStep(
    val messageId: String,
    val ordinal: Int,
    val name: String,
    val state: ToolCallRowView.State,
    val durationMs: Long?
) {

    /** Shares the collapsed row's formatting so the same call reads the same in both places. */
    val durationText: String?
        get() = durationMs?.takeIf { it > 0 }?.let { ToolCallRowView.formatDuration(it) }
}

/**
 * Projects a transcript onto its tool calls.
 *
 * Kept pure so the rules - which messages become steps, how they are numbered and when one counts
 * as still running - are checkable without a UI.
 */
object TimelineSteps {

    private const val ROLE_TOOL = "tool"

    fun from(messages: List<Message>): List<TimelineStep> {
        var ordinal = 0
        return messages.filter { it.role.equals(ROLE_TOOL, ignoreCase = true) || it.toolCallInfo != null }
            .map { message ->
                ordinal++
                TimelineStep(
                    messageId = message.id,
                    ordinal = ordinal,
                    name = message.toolCallInfo?.toolName?.takeIf { it.isNotBlank() } ?: ROLE_TOOL,
                    state = stateOf(message),
                    durationMs = message.metrics?.toolExecutionTimeMs?.toLong()?.takeIf { it > 0 }
                )
            }
    }

    /**
     * A call that is still streaming must never read as passed - the timeline is watched precisely
     * while the turn runs, so an early green tick would be the one lie that matters.
     */
    private fun stateOf(message: Message): ToolCallRowView.State = when {
        message.isToolStreaming -> ToolCallRowView.State.RUNNING
        message.toolCallInfo?.status == ToolCallStatus.EXECUTING -> ToolCallRowView.State.RUNNING
        message.toolCallInfo?.status == ToolCallStatus.FAILED -> ToolCallRowView.State.FAILED
        message.toolCallInfo?.result?.success == false -> ToolCallRowView.State.FAILED
        else -> ToolCallRowView.State.OK
    }
}
