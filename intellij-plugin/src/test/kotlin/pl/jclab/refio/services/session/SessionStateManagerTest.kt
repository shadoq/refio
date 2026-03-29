package pl.jclab.refio.services.session

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
}
