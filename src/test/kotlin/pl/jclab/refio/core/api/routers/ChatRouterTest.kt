package pl.jclab.refio.core.api.routers

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.models.api.ChatCosts
import pl.jclab.refio.core.models.api.ChatRequest
import pl.jclab.refio.core.models.api.ChatResponse
import pl.jclab.refio.core.models.api.LLMParams
import pl.jclab.refio.core.models.api.SummarizeResponse
import pl.jclab.refio.core.services.ChatService
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.db.MessageRole

class ChatRouterTest {

    private lateinit var chatService: ChatService
    private lateinit var chatMessageRepository: ChatMessageRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var chatRouter: ChatRouter

    @BeforeEach
    fun setup() {
        chatService = mockk()
        chatMessageRepository = mockk()
        taskRepository = mockk()
        chatRouter = ChatRouter(chatService, chatMessageRepository, taskRepository)
    }

    @Test
    fun `chat sends message and returns response`() = runBlocking {
        // Given
        val taskId = "task-123"
        val request = ChatRequest(
            taskId = taskId,
            mode = TaskMode.CHAT,
            input = "Hello",
            contextRefs = emptyList(),
            params = LLMParams(model = null, provider = null)
        )
        val expectedResponse = ChatResponse(
            requestId = "req-123",
            taskId = taskId,
            messageId = "msg-123",
            output = "Hi there!",
            costs = ChatCosts(
                tokensIn = 10,
                tokensOut = 20,
                usdEst = 0.001
            )
        )
        coEvery { chatService.chat(request, false, null) } returns expectedResponse

        // When
        val response = chatRouter.chat(request, stream = false, onChunk = null)

        // Then
        assertEquals(expectedResponse, response)
        coVerify { chatService.chat(request, false, null) }
    }

    @Test
    fun `getMessages returns messages for task`() = runBlocking {
        // Given
        val taskId = "task-123"
        coEvery { chatMessageRepository.findByTaskId(taskId) } returns emptyList()

        // When
        val response = chatRouter.getMessages(taskId)

        // Then
        assertEquals(0, response.count)
        assertEquals(0, response.messages.size)
        coVerify { chatMessageRepository.findByTaskId(taskId) }
    }

    @Test
    fun `deleteMessage removes message from repository`() = runBlocking {
        // Given
        val messageId = "msg-123"
        every { chatMessageRepository.delete(messageId) } returns true

        // When
        val result = chatRouter.deleteMessage(messageId)

        // Then
        assertTrue(result)
        verify { chatMessageRepository.delete(messageId) }
    }

    @Test
    fun `clearHistory removes all messages for task`() = runBlocking {
        // Given
        val taskId = "task-123"
        val messages = listOf(
            ChatMessage(
                id = "msg-1",
                taskId = taskId,
                role = MessageRole.USER,
                content = "Hello",
                metadata = null,
                toolCalls = null,
                toolCallId = null,
                tokensIn = null,
                tokensOut = null,
                cost = null,
                createdAt = System.currentTimeMillis()
            )
        )
        every { chatMessageRepository.findByTaskId(taskId) } returns messages
        every { chatMessageRepository.delete("msg-1") } returns true

        // When
        val result = chatRouter.clearHistory(taskId)

        // Then
        assertTrue(result)
        verify { chatMessageRepository.findByTaskId(taskId) }
        verify { chatMessageRepository.delete("msg-1") }
    }

    @Test
    fun `summarize generates conversation summary`() = runBlocking {
        // Given
        val taskId = "task-123"
        val expectedSummary = SummarizeResponse(
            summaryMessageId = "summary-1",
            summarizedCount = 2,
            summaryIndex = 1,
            content = "Summary of conversation"
        )
        coEvery { chatService.summarizeConversation(taskId, null) } returns expectedSummary

        // When
        val response = chatRouter.summarizeConversation(taskId)

        // Then
        assertEquals(expectedSummary, response)
        coVerify { chatService.summarizeConversation(taskId, null) }
    }
}
