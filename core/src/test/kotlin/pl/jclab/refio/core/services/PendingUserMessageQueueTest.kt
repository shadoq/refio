package pl.jclab.refio.core.services

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingUserMessageQueueTest {

    private val chatMessageRepository = mockk<ChatMessageRepository>(relaxed = true)
    private lateinit var queue: PendingUserMessageQueue

    @BeforeEach
    fun setup() {
        every { chatMessageRepository.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns mockk(relaxed = true)
        queue = PendingUserMessageQueue(chatMessageRepository)
    }

    @Test
    fun `enqueue saves message to repository with correct metadata`() {
        queue.enqueue("task-1", "hello agent")

        verify {
            chatMessageRepository.create(
                taskId = "task-1",
                role = MessageRole.USER,
                content = "hello agent",
                metadata = match { it!!.contains("mid_execution_input") }
            )
        }
    }

    @Test
    fun `consumePending returns false when no messages enqueued`() {
        assertFalse(queue.consumePending("task-1"))
    }

    @Test
    fun `consumePending returns true after enqueue`() {
        queue.enqueue("task-1", "hello")
        assertTrue(queue.consumePending("task-1"))
    }

    @Test
    fun `consumePending returns false on second call (flag cleared)`() {
        queue.enqueue("task-1", "hello")
        assertTrue(queue.consumePending("task-1"))
        assertFalse(queue.consumePending("task-1"))
    }

    @Test
    fun `consumePending is task-specific`() {
        queue.enqueue("task-1", "hello")

        assertFalse(queue.consumePending("task-2"))
        assertTrue(queue.consumePending("task-1"))
    }

    @Test
    fun `multiple enqueues for same task still result in single consumePending true`() {
        queue.enqueue("task-1", "first")
        queue.enqueue("task-1", "second")

        assertTrue(queue.consumePending("task-1"))
        assertFalse(queue.consumePending("task-1"))
    }

    @Test
    fun `enqueue after consume re-sets the flag`() {
        queue.enqueue("task-1", "first")
        assertTrue(queue.consumePending("task-1"))

        queue.enqueue("task-1", "second")
        assertTrue(queue.consumePending("task-1"))
    }
}
