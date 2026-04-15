package pl.jclab.refio.core.session

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.Session
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.api.models.TaskStatus
import kotlin.test.assertEquals

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
}
