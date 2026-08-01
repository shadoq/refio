package pl.jclab.refio.ui.execution

import org.junit.jupiter.api.Test
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.ToolCallDisplayInfo
import pl.jclab.refio.api.models.ToolCallResult
import pl.jclab.refio.api.models.ToolCallStatus
import pl.jclab.refio.api.models.ToolDisplayType
import pl.jclab.refio.core.db.MessageMetrics
import pl.jclab.refio.ui.components.chat.toolcall.ToolCallRowView
import kotlin.test.assertEquals

/**
 * The timeline is what the user clicks to get back to a tool call in a long transcript, so the
 * rules under test are: only tool calls become rows, they carry the id of the bubble they point
 * at, and a call still in flight is never shown as finished.
 */
class TimelineStepsTest {

    private fun message(
        id: String,
        role: String,
        toolName: String? = null,
        status: ToolCallStatus = ToolCallStatus.COMPLETED,
        success: Boolean? = null,
        streaming: Boolean = false,
        executionMs: Int = 0
    ) = Message(
        id = id,
        taskId = "task-1",
        role = role,
        content = "",
        createdAt = 0L,
        isToolStreaming = streaming,
        metrics = if (executionMs > 0) MessageMetrics(toolExecutionTimeMs = executionMs) else null,
        toolCallInfo = toolName?.let {
            ToolCallDisplayInfo(
                toolName = it,
                toolCallId = "call-$id",
                displayType = ToolDisplayType.SIMPLE,
                parameters = emptyMap(),
                status = status,
                result = success?.let { ok -> ToolCallResult(success = ok, summary = "") }
            )
        }
    )

    @Test
    fun `only tool calls become rows so the timeline stays a list of work, not of chatter`() {
        val steps = TimelineSteps.from(
            listOf(
                message("m1", "user"),
                message("m2", "assistant"),
                message("m3", "tool", toolName = "read_file"),
                message("m4", "assistant"),
                message("m5", "tool", toolName = "advance_code_editing")
            )
        )

        assertEquals(listOf("read_file", "advance_code_editing"), steps.map { it.name })
    }

    @Test
    fun `a row carries the id of the bubble it points at so clicking it can reach the transcript`() {
        val steps = TimelineSteps.from(listOf(message("m7", "tool", toolName = "grep_search")))

        assertEquals("m7", steps.single().messageId)
    }

    @Test
    fun `steps are numbered in transcript order so the row matches the step the user was told about`() {
        val steps = TimelineSteps.from(
            listOf(
                message("m1", "tool", toolName = "read_file"),
                message("m2", "assistant"),
                message("m3", "tool", toolName = "run_terminal_command")
            )
        )

        assertEquals(listOf(1, 2), steps.map { it.ordinal })
    }

    @Test
    fun `a call still streaming is shown as running, never as passed`() {
        val steps = TimelineSteps.from(
            listOf(message("m1", "tool", toolName = "advance_code_editing", streaming = true))
        )

        assertEquals(ToolCallRowView.State.RUNNING, steps.single().state)
    }

    @Test
    fun `an executing status is running even when the message is not streaming`() {
        val steps = TimelineSteps.from(
            listOf(message("m1", "tool", toolName = "read_file", status = ToolCallStatus.EXECUTING))
        )

        assertEquals(ToolCallRowView.State.RUNNING, steps.single().state)
    }

    @Test
    fun `a failed result is shown as failed so a broken step is visible without expanding it`() {
        val steps = TimelineSteps.from(
            listOf(message("m1", "tool", toolName = "run_terminal_command", success = false))
        )

        assertEquals(ToolCallRowView.State.FAILED, steps.single().state)
    }

    @Test
    fun `duration is only shown when it was actually measured`() {
        val measured = TimelineSteps.from(
            listOf(message("m1", "tool", toolName = "read_file", executionMs = 11))
        ).single()
        val unmeasured = TimelineSteps.from(
            listOf(message("m2", "tool", toolName = "read_file"))
        ).single()

        assertEquals("11ms", measured.durationText)
        assertEquals(null, unmeasured.durationText)
    }
}
