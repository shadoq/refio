package pl.jclab.refio.core.db.repositories

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.core.db.*
import pl.jclab.refio.testutil.TestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Testy dla ChatMessageRepository.
 */
class ChatMessageRepositoryTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var repository: ChatMessageRepository
    private lateinit var taskRepository: TaskRepository

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        repository = ChatMessageRepository()
        taskRepository = TaskRepository()
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    private fun createTestTask(id: String = "task-123"): Task {
        return transaction {
            taskRepository.create(
                name = "Test Task",
                mode = TaskMode.AGENT,
                projectId = "project-123",
                projectPath = "/test/project",
                id = id
            )
        }
    }

    @Nested
    inner class CreateTests {

        @Test
        fun `should create user message`() {
            transaction {
                // Given
                val task = createTestTask()

                // When
                val message = repository.create(
                    taskId = task.id,
                    role = MessageRole.USER,
                    content = "Hello, AI!"
                )

                // Then
                assertNotNull(message.id)
                assertEquals(task.id, message.taskId)
                assertEquals(MessageRole.USER, message.role)
                assertEquals("Hello, AI!", message.content)
            }
        }

        @Test
        fun `should create assistant message with thinking`() {
            transaction {
                // Given
                val task = createTestTask()

                // When
                val message = repository.create(
                    taskId = task.id,
                    role = MessageRole.ASSISTANT,
                    content = "Let me help",
                    thinking = "Thinking process..."
                )

                // Then
                assertNotNull(message.id)
                assertEquals(MessageRole.ASSISTANT, message.role)
                assertEquals("Let me help", message.content)
                assertEquals("Thinking process...", message.thinking)
            }
        }

        @Test
        fun `should create tool result message`() {
            transaction {
                // Given
                val task = createTestTask()
                val toolCallId = "call-123"

                // When
                val message = repository.createToolResult(
                    taskId = task.id,
                    toolCallId = toolCallId,
                    result = "Tool executed successfully"
                )

                // Then
                assertNotNull(message.id)
                assertEquals(MessageRole.TOOL, message.role)
                assertEquals(toolCallId, message.toolCallId)
                assertEquals("Tool executed successfully", message.content)
            }
        }
    }

    @Nested
    inner class FindTests {

        @Test
        fun `should find message by ID`() {
            transaction {
                // Given
                val task = createTestTask()
                val created = repository.create(
                    taskId = task.id,
                    role = MessageRole.USER,
                    content = "Test message"
                )

                // When
                val found = repository.findById(created.id)

                // Then
                assertNotNull(found)
                assertEquals("Test message", found.content)
            }
        }

        @Test
        fun `should find all messages for task`() {
            transaction {
                // Given
                val task = createTestTask()
                val msg1 = repository.create(taskId = task.id, role = MessageRole.USER, content = "Message 1")
                val msg2 = repository.create(taskId = task.id, role = MessageRole.ASSISTANT, content = "Message 2")
                val msg3 = repository.create(taskId = task.id, role = MessageRole.USER, content = "Message 3")

                // When
                val messages = repository.findByTaskId(task.id)

                // Then - ordered by createdAt ASC, then id ASC
                assertEquals(3, messages.size)
                assertEquals(msg1.id, messages[0].id)
                assertEquals(msg2.id, messages[1].id)
                assertEquals(msg3.id, messages[2].id)
            }
        }

        @Test
        fun `should find messages by role`() {
            transaction {
                // Given
                val task = createTestTask()
                repository.create(taskId = task.id, role = MessageRole.USER, content = "User message")
                repository.create(taskId = task.id, role = MessageRole.ASSISTANT, content = "Assistant message")
                repository.create(taskId = task.id, role = MessageRole.USER, content = "Another user message")

                // When
                val userMessages = repository.findByRole(task.id, MessageRole.USER)
                val assistantMessages = repository.findByRole(task.id, MessageRole.ASSISTANT)

                // Then
                assertEquals(2, userMessages.size)
                assertEquals(1, assistantMessages.size)
            }
        }
    }

    @Nested
    inner class UpdateTests {

        @Test
        fun `should update message metadata`() {
            transaction {
                // Given
                val task = createTestTask()
                val message = repository.create(
                    taskId = task.id,
                    role = MessageRole.USER,
                    content = "Test"
                )

                // When
                val updated = repository.updateMetadata(message.id, """{"key": "value"}""")

                // Then
                assertNotNull(updated)
                assertEquals("""{"key": "value"}""", updated.metadata)
            }
        }

        @Test
        fun `should update content and metadata`() {
            transaction {
                // Given
                val task = createTestTask()
                val message = repository.create(
                    taskId = task.id,
                    role = MessageRole.USER,
                    content = "Original"
                )

                // When
                val updated = repository.updateContentAndMetadata(
                    id = message.id,
                    content = "Updated",
                    metadata = """{"updated": true}"""
                )

                // Then
                assertNotNull(updated)
                assertEquals("Updated", updated.content)
                assertEquals("""{"updated": true}""", updated.metadata)
            }
        }
    }

    @Nested
    inner class DeleteTests {

        @Test
        fun `should delete message`() {
            transaction {
                // Given
                val task = createTestTask()
                val message = repository.create(
                    taskId = task.id,
                    role = MessageRole.USER,
                    content = "To delete"
                )

                // When
                val deleted = repository.delete(message.id)

                // Then
                assertTrue(deleted)
                assertNull(repository.findById(message.id))
            }
        }

        @Test
        fun `should return false when deleting nonexistent message`() {
            transaction {
                // When
                val deleted = repository.delete("nonexistent")

                // Then
                assertTrue(!deleted)
            }
        }
    }
}
