package pl.jclab.refio.core.services

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.models.api.ChatRequest
import pl.jclab.refio.core.models.api.LLMParams
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.testutil.MockFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ChatServiceTest {

    private lateinit var taskRepository: TaskRepository
    private lateinit var chatMessageRepository: ChatMessageRepository
    private lateinit var configService: ConfigService
    private lateinit var llmClient: LLMClient
    private lateinit var promptsService: PromptsService
    private lateinit var toolDescriptionBuilder: ToolDescriptionBuilder
    private lateinit var chatService: ChatService

    private val testTask = MockFactory.createTask(
        id = "task-1",
        mode = TaskMode.CHAT,
        status = TaskStatus.NEW
    )

    private val testLLMResponse = LLMResponse(
        content = "Hello! I'm the assistant.",
        usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
        model = "test-model",
        provider = "test",
        cost = 0.001
    )

    @BeforeEach
    fun setup() {
        taskRepository = mockk(relaxed = true)
        chatMessageRepository = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        llmClient = mockk(relaxed = true)
        promptsService = mockk(relaxed = true)
        toolDescriptionBuilder = mockk(relaxed = true)

        // Default stubs
        every { taskRepository.findById("task-1") } returns testTask
        every { chatMessageRepository.findByTaskId(any()) } returns emptyList()
        every { chatMessageRepository.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            MockFactory.createChatMessage(
                taskId = firstArg(),
                role = secondArg(),
                content = thirdArg()
            )
        }
        every { configService.getModel(any(), any()) } returns Pair("test-model", "test")
        every { configService.getTyped(ConfigKeys.READ_ONLY_MODE) } returns false
        every { configService.getTyped(ConfigKeys.AUTO_OPTIMIZE_PERCENTAGE) } returns 0  // Disable auto-optimize for tests
        every { configService.getTyped(ConfigKeys.MAX_CONTEXT_SIZE, any<String>()) } returns 128000
        every { promptsService.getSystemPrompt(any(), any()) } returns "You are a helpful assistant."

        coEvery { llmClient.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns testLLMResponse

        chatService = ChatService(
            taskRepository = taskRepository,
            chatMessageRepository = chatMessageRepository,
            configService = configService,
            llmClient = llmClient,
            promptsService = promptsService,
            toolDescriptionBuilder = toolDescriptionBuilder,
            contextService = null,
            projectRoot = null,
        )
    }

    @Nested
    inner class ChatFlowTests {

        @Test
        fun `should reject non-CHAT mode`() = runTest {
            val request = ChatRequest(
                taskId = "task-1",
                mode = TaskMode.PLAN,
                input = "Hello"
            )

            assertFailsWith<IllegalArgumentException> {
                chatService.chat(request)
            }
        }

        @Test
        fun `should update task status from NEW to RUNNING`() = runTest {
            val request = ChatRequest(
                taskId = "task-1",
                mode = TaskMode.CHAT,
                input = "Hello"
            )

            chatService.chat(request)

            verify { taskRepository.update(id = "task-1", status = TaskStatus.RUNNING) }
        }

        @Test
        fun `should save user message to database`() = runTest {
            val request = ChatRequest(
                taskId = "task-1",
                mode = TaskMode.CHAT,
                input = "Hello world"
            )

            chatService.chat(request)

            verify { chatMessageRepository.create("task-1", MessageRole.USER, "Hello world", any()) }
        }

        @Test
        fun `should return chat response with content`() = runTest {
            val request = ChatRequest(
                taskId = "task-1",
                mode = TaskMode.CHAT,
                input = "Hello"
            )

            val response = chatService.chat(request)

            assertNotNull(response)
            assertEquals("Hello! I'm the assistant.", response.output)
        }

        @Test
        fun `should create task if not found`() = runTest {
            every { taskRepository.findById("new-task") } returns null
            every { taskRepository.create(any(), any(), any(), any(), any()) } returns
                MockFactory.createTask(id = "new-task", mode = TaskMode.CHAT, status = TaskStatus.NEW)

            val request = ChatRequest(
                taskId = "new-task",
                mode = TaskMode.CHAT,
                input = "Hello"
            )

            chatService.chat(request)

            verify { taskRepository.create(any(), eq(TaskMode.CHAT), any(), any(), any()) }
        }

        @Test
        fun `should use explicit model and provider from request params`() = runTest {
            val request = ChatRequest(
                taskId = "task-1",
                mode = TaskMode.CHAT,
                input = "Hello",
                params = LLMParams(model = "gpt-4o", provider = "openai")
            )

            chatService.chat(request)

            coEvery {
                llmClient.complete(
                    provider = eq("openai"),
                    model = eq("gpt-4o"),
                    messages = any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
                )
            }
        }
    }

    @Nested
    inner class ContextRefTests {

        @Test
        fun `should reject too many context refs`() = runTest {
            val tooManyRefs = (1..51).map {
                mockk<pl.jclab.refio.api.models.ContextReference>(relaxed = true)
            }
            val request = ChatRequest(
                taskId = "task-1",
                mode = TaskMode.CHAT,
                input = "Hello",
                contextRefs = tooManyRefs
            )

            assertFailsWith<IllegalArgumentException> {
                chatService.chat(request)
            }
        }
    }

    @Nested
    inner class StandaloneCompatibilityTests {

        @Test
        fun `should work in standalone CLI mode`() = runTest {
            // ChatService constructed without IntelliJ Project (standalone CLI mode)
            val service = ChatService(
                taskRepository = taskRepository,
                chatMessageRepository = chatMessageRepository,
                configService = configService,
                llmClient = llmClient,
                promptsService = promptsService,
                toolDescriptionBuilder = toolDescriptionBuilder,
                contextService = null,
                projectRoot = null,
            )

            val request = ChatRequest(
                taskId = "task-1",
                mode = TaskMode.CHAT,
                input = "Hello"
            )

            val response = service.chat(request)
            assertNotNull(response)
        }

        @Test
        fun `should work with contextService null`() = runTest {
            val request = ChatRequest(
                taskId = "task-1",
                mode = TaskMode.CHAT,
                input = "Hello"
            )

            // contextService is null — should not crash
            val response = chatService.chat(request)
            assertNotNull(response)
        }
    }
}
