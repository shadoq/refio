package pl.jclab.refio.core.services

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.testutil.TestDatabase
import kotlin.test.*

class ConversationCompactorTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var llmClient: LLMClient
    private lateinit var chatMessageRepository: ChatMessageRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var configService: ConfigService
    private lateinit var tokenEstimator: PromptTokenEstimator
    private lateinit var compactor: ConversationCompactor

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        llmClient = mockk(relaxed = true)
        chatMessageRepository = ChatMessageRepository()
        taskRepository = TaskRepository()
        configService = mockk(relaxed = true)
        tokenEstimator = PromptTokenEstimator()

        every { configService.getModel(ModelOperation.WEAK, any()) } returns Pair("gpt-4o-mini", "openai")

        compactor = ConversationCompactor(
            llmClient, chatMessageRepository, taskRepository, configService, tokenEstimator
        )
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    private fun makeLLMResponse(content: String) = LLMResponse(
        content = content,
        usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
        model = "gpt-4o-mini",
        provider = "openai",
        cost = 0.001
    )

    private fun createTaskWithMessages(taskId: String, count: Int) {
        transaction {
            taskRepository.create(
                name = "Test Task", mode = TaskMode.AGENT,
                projectId = "proj-1", projectPath = "/test", id = taskId
            )
            (1..count).forEach { i ->
                chatMessageRepository.create(
                    taskId = taskId, role = MessageRole.USER, content = "Message $i"
                )
            }
        }
    }

    @Nested
    inner class MaybeCompact {

        @Test
        fun `should not compact when below threshold`() = runTest {
            val result = compactor.maybeCompact("task-1", 5000, 10000, 0.85)
            assertFalse(result)
        }

        @Test
        fun `should compact when at threshold`() = runTest {
            createTaskWithMessages("task-1", 8)

            coEvery { llmClient.complete(provider = any(), model = any(), messages = any(), systemPrompt = any(), taskId = any(), source = any(), maxTokens = any()) } returns
                makeLLMResponse("Summary of conversation")

            val result = compactor.maybeCompact("task-1", 8500, 10000, 0.85)
            assertTrue(result)
        }

        @Test
        fun `should compact when above threshold`() = runTest {
            createTaskWithMessages("task-1", 6)

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
            createTaskWithMessages("task-1", 3)

            val result = compactor.compact("task-1", 5000)
            assertFalse(result)
        }

        @Test
        fun `should summarize older messages and keep last 4`() = runTest {
            createTaskWithMessages("task-1", 8)

            coEvery { llmClient.complete(provider = any(), model = any(), messages = any(), systemPrompt = any(), taskId = any(), source = any(), maxTokens = any()) } returns
                makeLLMResponse("Concise summary")

            val result = compactor.compact("task-1", 5000)

            assertTrue(result)

            // After compaction: 1 system summary + 4 kept raw = 5 messages
            val remaining = chatMessageRepository.findByTaskId("task-1")
            assertEquals(5, remaining.size, "Should have 5 messages after compaction (1 summary + 4 kept)")
            assertEquals(1, remaining.count { it.role == MessageRole.SYSTEM }, "Should have exactly 1 system summary")
        }

        @Test
        fun `should increment compaction count`() = runTest {
            assertEquals(0, compactor.getCompactionCount())

            createTaskWithMessages("task-1", 6)

            coEvery { llmClient.complete(provider = any(), model = any(), messages = any(), systemPrompt = any(), taskId = any(), source = any(), maxTokens = any()) } returns
                makeLLMResponse("Summary")

            compactor.compact("task-1", 5000)

            assertEquals(1, compactor.getCompactionCount())
        }

        @Test
        fun `should use WEAK model for summarization`() = runTest {
            createTaskWithMessages("task-1", 6)

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
                    maxTokens = 1200
                )
            }
        }

        @Test
        fun `should not compact when all messages are in keepRaw window`() = runTest {
            createTaskWithMessages("task-1", 4)

            val result = compactor.compact("task-1", 5000)
            assertFalse(result)
        }
    }
}
