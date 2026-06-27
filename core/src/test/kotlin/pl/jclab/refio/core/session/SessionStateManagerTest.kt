package pl.jclab.refio.core.session

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.Session
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.api.models.TaskStatus
import pl.jclab.refio.core.api.ToolCallProgress
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionStateManagerTest {

    @Test
    fun `setActiveSession updates state`() {
        val manager = SessionStateManager()
        val session = Session(
            id = "session-1",
            name = "Test",
            mode = TaskMode.CHAT,
            status = TaskStatus.PENDING,
            createdAt = 1L,
            updatedAt = 1L,
            executionMode = ExecutionMode.INTERACTIVE
        )

        manager.setActiveSession(session)

        assertEquals(session, manager.activeSession.value)
    }

    @Test
    fun `appendMessage adds message`() = runBlocking {
        val manager = SessionStateManager()
        val message = Message(
            id = "msg-1",
            taskId = "session-1",
            role = "system",
            content = "hello",
            createdAt = 1L
        )

        manager.appendMessage(message)

        assertEquals(1, manager.messages.value.size)
        assertEquals(message, manager.messages.value.first())
    }

    @Test
    fun `scenario - set session then append multiple messages preserves order`() = runBlocking {
        val manager = SessionStateManager()
        val session = Session(
            id = "session-1",
            name = "Test",
            mode = TaskMode.CHAT,
            status = TaskStatus.PENDING,
            createdAt = 1L,
            updatedAt = 1L,
            executionMode = ExecutionMode.INTERACTIVE
        )
        manager.setActiveSession(session)

        val messages = (1..5).map {
            Message(
                id = "msg-$it",
                taskId = "session-1",
                role = if (it % 2 == 0) "assistant" else "user",
                content = "message $it",
                createdAt = it.toLong()
            )
        }
        messages.forEach { manager.appendMessage(it) }

        assertEquals(session, manager.activeSession.value)
        assertEquals(5, manager.messages.value.size)
        assertEquals(messages.map { it.id }, manager.messages.value.map { it.id })
    }

    @Test
    fun `setActiveSession followed by different session updates to new one`() {
        val manager = SessionStateManager()
        val first = Session(
            id = "session-1", name = "First", mode = TaskMode.CHAT, status = TaskStatus.PENDING,
            createdAt = 1L, updatedAt = 1L, executionMode = ExecutionMode.INTERACTIVE
        )
        val second = Session(
            id = "session-2", name = "Second", mode = TaskMode.PLAN, status = TaskStatus.RUNNING,
            createdAt = 2L, updatedAt = 2L, executionMode = ExecutionMode.INTERACTIVE
        )

        manager.setActiveSession(first)
        assertEquals("session-1", manager.activeSession.value?.id)

        manager.setActiveSession(second)
        assertEquals("session-2", manager.activeSession.value?.id)
        assertEquals(TaskMode.PLAN, manager.activeSession.value?.mode)
    }

    // The transient "model is building a tool call" indicator (docs/0064) is cleared on the
    // stream's completion chunk. But a STOP/cancel or switching to another session never
    // delivers that chunk, so a stale "⚙ building <tool>" must not survive the switch — the
    // new/other session would otherwise render a tool-call indicator that belongs to a turn
    // that is already gone.
    @Test
    fun `switching to a different session clears a stale tool-call progress`() {
        val manager = SessionStateManager()
        val first = Session(
            id = "session-1", name = "First", mode = TaskMode.AGENT, status = TaskStatus.RUNNING,
            createdAt = 1L, updatedAt = 1L, executionMode = ExecutionMode.INTERACTIVE
        )
        val second = Session(
            id = "session-2", name = "Second", mode = TaskMode.AGENT, status = TaskStatus.PENDING,
            createdAt = 2L, updatedAt = 2L, executionMode = ExecutionMode.INTERACTIVE
        )

        manager.setActiveSession(first)
        manager.setToolCallProgress(ToolCallProgress(index = 0, name = "read_file", accumulatedArguments = "{\"pa"))
        assertEquals("read_file", manager.toolCallProgress.value?.name)

        manager.setActiveSession(second)

        assertNull(manager.toolCallProgress.value, "Stale tool-call progress must not leak into the next session")
    }

    // A same-session metric refresh (auto-naming, token bumps) goes through setActiveSession with the
    // SAME id while a turn is mid-flight. That must NOT wipe the live indicator, or the "⚙ building"
    // hint would flicker off every time the task row updates during the very turn that produced it.
    @Test
    fun `re-setting the same session keeps the live tool-call progress`() {
        val manager = SessionStateManager()
        val session = Session(
            id = "session-1", name = "First", mode = TaskMode.AGENT, status = TaskStatus.RUNNING,
            createdAt = 1L, updatedAt = 1L, executionMode = ExecutionMode.INTERACTIVE
        )

        manager.setActiveSession(session)
        manager.setToolCallProgress(ToolCallProgress(index = 0, name = "grep_search", accumulatedArguments = "{}"))

        manager.setActiveSession(session.copy(tokensIn = 42, updatedAt = 99L))

        assertEquals("grep_search", manager.toolCallProgress.value?.name)
    }
}
