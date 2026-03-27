package pl.jclab.refio.core.services

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import kotlin.test.*

class ConversationCompactorTest {

    private lateinit var llmClient: LLMClient
    private lateinit var chatMessageRepository: ChatMessageRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var configService: ConfigService
    private lateinit var tokenEstimator: PromptTokenEstimator
    private lateinit var compactor: ConversationCompactor

    @BeforeEach
    fun setup() {
        llmClient = mockk(relaxed = true)
        chatMessageRepository = mockk(relaxed = true)
        taskRepository = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        tokenEstimator = PromptTokenEstimator()

        every { configService.getModel(ModelOperation.WEAK, any()) } returns Pair("gpt-4o-mini", "openai")

        compactor = ConversationCompactor(
            llmClient, chatMessageRepository, taskRepository, configService, tokenEstimator
        )
    }

    private fun makeLLMResponse(content: String) = LLMResponse(
        content = content,
        usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
        model = "gpt-4o-mini",
        provider = "openai",
        cost = 0.001
    )

    private fun makeMessage(
        id: String,
        role: MessageRole = MessageRole.USER,
        content: String = "Message $id"
    ) = ChatMessage(
        id = id,
        taskId = "task-1",
        role = role,
        content = content,
        metadata = null,
        toolCalls = null,
        toolCallId = null,
        tokensIn = null,
        tokensOut = null,
        cost = null,
        createdAt = System.currentTimeMillis()
    )

    @Nested
    inner class MaybeCompact {

        @Test
        fun `should not compact when below threshold`() = runTest {
            val result = compactor.maybeCompact("task-1", 5000, 10000, 0.85)
            assertFalse(result)
            verify(exactly = 0) { chatMessageRepository.findByTaskId(any()) }
        }

        @Test
        fun `should compact when at threshold`() = runTest {
            every { chatMessageRepository.findByTaskId("task-1") } returns
                (1..8).map { makeMessage("$it") }

            coEvery { llmClient.complete(provider = any(), model = any(), messages = any(), systemPrompt = any(), taskId = any(), source = any(), maxTokens = any()) } returns
                makeLLMResponse("Summary of conversation")

            val result = compactor.maybeCompact("task-1", 8500, 10000, 0.85)
            assertTrue(result)
        }

        @Test
        fun `should compact when above threshold`() = runTest {
            every { chatMessageRepository.findByTaskId("task-1") } returns
                (1..6).map { makeMessage("$it") }

            coEvery { llmClient.complete(provider = any(), model = any(), messages = any(), systemPrompt = any(), taskId = any(), source = any(), maxTokens = any()) } returns
                makeLLMResponse("Summary")

            val result = compactor.maybeCompact("task-1", 9500, 10000, 0.85)
            assertTrue(result)
        }
    }

    @Nested
    inner class Compact {

        @Test
        fun `should not compact with fewer than 4 messages`() = runTest {
            every { chatMessageRepository.findByTaskId("task-1") } returns
                (1..3).map { makeMessage("$it") }

            val result = compactor.compact("task-1", 5000)
            assertFalse(result)
        }

        @Test
        fun `should summarize older messages and keep last 4`() = runTest {
            val messages = (1..8).map { makeMessage("msg-$it") }
            every { chatMessageRepository.findByTaskId("task-1") } returns messages

            coEvery { llmClient.complete(provider = any(), model = any(), messages = any(), systemPrompt = any(), taskId = any(), source = any(), maxTokens = any()) } returns
                makeLLMResponse("Concise summary")

            val result = compactor.compact("task-1", 5000)

            assertTrue(result)

            // Should delete old messages (first 4 of 8)
            verify(exactly = 4) { chatMessageRepository.delete(any()) }
            verify { chatMessageRepository.delete("msg-1") }
            verify { chatMessageRepository.delete("msg-2") }
            verify { chatMessageRepository.delete("msg-3") }
            verify { chatMessageRepository.delete("msg-4") }
        }

        @Test
        fun `should increment compaction count`() = runTest {
            assertEquals(0, compactor.getCompactionCount())

            every { chatMessageRepository.findByTaskId("task-1") } returns
                (1..6).map { makeMessage("msg-$it") }

            coEvery { llmClient.complete(provider = any(), model = any(), messages = any(), systemPrompt = any(), taskId = any(), source = any(), maxTokens = any()) } returns
                makeLLMResponse("Summary")

            compactor.compact("task-1", 5000)

            assertEquals(1, compactor.getCompactionCount())
        }

        @Test
        fun `should use WEAK model for summarization`() = runTest {
            every { chatMessageRepository.findByTaskId("task-1") } returns
                (1..6).map { makeMessage("msg-$it") }

            coEvery { llmClient.complete(provider = any(), model = any(), messages = any(), systemPrompt = any(), taskId = any(), source = any(), maxTokens = any()) } returns
                makeLLMResponse("Summary")

            compactor.compact("task-1", 5000)

            verify { configService.getModel(ModelOperation.WEAK, "task-1") }
            coVerify {
                llmClient.complete(
                    provider = "openai",
                    model = "gpt-4o-mini",
                    messages = any(),
                    systemPrompt = any(),
                    taskId = "task-1",
                    source = "ConversationCompactor",
                    maxTokens = 800
                )
            }
        }

        @Test
        fun `should not compact when all messages are in keepRaw window`() = runTest {
            every { chatMessageRepository.findByTaskId("task-1") } returns
                (1..4).map { makeMessage("msg-$it") }

            val result = compactor.compact("task-1", 5000)
            assertFalse(result)
        }
    }
}
