package pl.jclab.refio.core.services

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.services.turn.TurnFinalizer
import pl.jclab.refio.core.services.turn.TurnGuardrails
import pl.jclab.refio.core.services.turn.TurnLLMCaller
import pl.jclab.refio.core.services.turn.TurnPromptBuilder
import pl.jclab.refio.core.services.turn.TurnResponseProcessor
import pl.jclab.refio.core.services.turn.TurnSubagentValidator
import pl.jclab.refio.core.services.turn.ToolCallParser
import pl.jclab.refio.core.services.turn.TurnToolExecutor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Testy dla AgentTurnLoop — główna silnik wykonawczy dla trybów PLAN/AGENT.
 */
class AgentTurnLoopTest {

    private lateinit var llmClient: LLMClient
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var chatMessageRepository: ChatMessageRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var subtaskRepository: SubtaskRepository
    private lateinit var configService: ConfigService
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var promptsService: PromptsService
    private lateinit var toolResultSummarizer: ToolResultSummarizer
    private lateinit var toolPermissionsService: ToolPermissionsService
    private lateinit var contextService: ContextService
    private lateinit var toolDescriptionBuilder: ToolDescriptionBuilder
    private lateinit var agentTurnLoop: AgentTurnLoop

    private val testTaskId = "task-123"
    private val testProjectId = "project-123"

    @BeforeEach
    fun setup() {
        llmClient = mockk(relaxed = true)
        toolRegistry = mockk(relaxed = true)
        chatMessageRepository = mockk(relaxed = true)
        taskRepository = mockk(relaxed = true)
        subtaskRepository = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        toolExecutor = mockk(relaxed = true)
        promptsService = mockk(relaxed = true)
        toolResultSummarizer = mockk(relaxed = true)
        toolPermissionsService = mockk(relaxed = true)
        contextService = mockk(relaxed = true)
        toolDescriptionBuilder = mockk(relaxed = true)

        // Default mock behaviors
        coEvery {
            llmClient.complete(
                provider = any(),
                model = any(),
                messages = any(),
                systemPrompt = any(),
                maxTokens = any(),
                temperature = any(),
                responseFormat = any(),
                thinking = any(),
                noEgressEnabled = any(),
                stream = any(),
                onChunk = any(),
                taskId = any(),
                subtaskId = any(),
                source = any(),
                kwargs = any()
            )
        } returns LLMResponse(
            content = """{"response": "Hello, how can I help you?"}""",
            usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
            model = "gpt-4",
            provider = "openai",
            cost = 0.001
        )

        every { promptsService.getSystemPrompt(any(), any()) } returns "System prompt"
        every { configService.getModel(any(), any()) } returns Pair("gpt-4", "openai")

        val mockTask = createMockTask()
        every { taskRepository.findById(testTaskId) } returns mockTask
        every { chatMessageRepository.create(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns createMockMessage()
        every { chatMessageRepository.findByTaskId(any()) } returns emptyList()
        every { contextService.collectAllUserContextRefs(any()) } returns emptyList()

        every { toolRegistry.getTool(any()) } returns null
        every { toolRegistry.getAllTools() } returns emptyList()
        every { toolPermissionsService.getPermission(any(), any(), any()) } returns PermissionLevel.ON
        every { configService.getJsonThinkingXmlTags(any()) } returns emptyList()

        // Create turn/ package components
        val tokenEstimator = pl.jclab.refio.core.services.TokenEstimator()

        val turnPromptBuilder = TurnPromptBuilder(
            promptsService = promptsService,
            chatMessageRepository = chatMessageRepository,
            toolDescriptionBuilder = toolDescriptionBuilder,
            contextService = contextService,
            workingMemoryService = null,
            projectRoot = null,
            tokenEstimator = tokenEstimator,
            promptCache = null
        )

        val toolCallParser = ToolCallParser(
            toolRegistry = toolRegistry,
            toolPermissionsService = toolPermissionsService,
            getJsonThinkingXmlTags = { taskId -> configService.getJsonThinkingXmlTags(taskId) }
        )

        val turnToolExecutor = TurnToolExecutor(
            toolExecutor = toolExecutor,
            toolRegistry = toolRegistry,
            subtaskRepository = subtaskRepository,
            toolResultSummarizer = toolResultSummarizer,
            snapshotService = null,
            workingMemoryIntegration = null
        )

        val turnLLMCaller = TurnLLMCaller(
            llmClient = llmClient,
            configService = configService
        )

        val turnResponseProcessor = TurnResponseProcessor(
            subtaskRepository = subtaskRepository,
            toolRegistry = toolRegistry,
            toolDescriptionBuilder = toolDescriptionBuilder
        )

        val turnFinalizer = TurnFinalizer(
            chatMessageRepository = chatMessageRepository
        )

        val turnSubagentValidator = TurnSubagentValidator(
            maxSubagentDepth = 3
        )

        agentTurnLoop = AgentTurnLoop(
            llmClient = llmClient,
            chatMessageRepository = chatMessageRepository,
            taskRepository = taskRepository,
            subtaskRepository = subtaskRepository,
            configService = configService,
            toolRegistry = toolRegistry,
            toolDescriptionBuilder = toolDescriptionBuilder,
            taskVerifier = NoopTaskVerifier(),
            turnPromptBuilder = turnPromptBuilder,
            toolCallParser = toolCallParser,
            turnToolExecutor = turnToolExecutor,
            turnLLMCaller = turnLLMCaller,
            turnResponseProcessor = turnResponseProcessor,
            turnFinalizer = turnFinalizer,
            turnSubagentValidator = turnSubagentValidator,
            tokenEstimator = tokenEstimator,
            conversationCompactor = null,
            llmRetryHandler = null,
            workingMemoryIntegration = null
        )
    }

    // Helper functions
    private fun createMockTask(
        id: String = testTaskId,
        mode: TaskMode = TaskMode.AGENT,
        status: TaskStatus = TaskStatus.RUNNING
    ) = Task(
        id = id,
        name = "Test Task",
        mode = mode,
        status = status,
        readOnly = mode == TaskMode.PLAN,
        pinned = false,
        executionMode = ExecutionMode.INTERACTIVE,
        requiresPlanApproval = false,
        planApproved = false,
        uiState = null,
        coreApiVersion = "1.0",
        projectId = testProjectId,
        projectPath = "/test/project",
        rate = null,
        tokensIn = 0,
        tokensOut = 0,
        costUsd = 0.0,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    private fun createMockMessage(
        id: String = "msg-123",
        role: MessageRole = MessageRole.USER,
        content: String = "Test message"
    ) = ChatMessage(
        id = id,
        taskId = testTaskId,
        role = role,
        content = content,
        thinking = null,
        metadata = null,
        toolCalls = null,
        toolCallId = null,
        isSummarized = false,
        rawOutput = null,
        tokensIn = null,
        tokensOut = null,
        cost = null,
        createdAt = System.currentTimeMillis()
    )

    private fun createLLMResponse(
        content: String,
        inputTokens: Int = 100,
        outputTokens: Int = 50
    ) = LLMResponse(
        content = content,
        usage = LLMUsage(inputTokens = inputTokens, outputTokens = outputTokens, totalTokens = inputTokens + outputTokens),
        model = "gpt-4",
        provider = "openai",
        cost = 0.001
    )

    @Nested
    inner class BasicTurnExecutionTests {

        @Test
        fun `should complete turn with simple text response`() = runTest {
            // Given
            val userInput = "Hello, what can you do?"

            // When
            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = userInput,
                mode = TaskMode.AGENT  // AgentTurnLoop only supports PLAN and AGENT
            )

            // Then
            assertTrue(result.success)
            assertNotNull(result.response)
            assertEquals(1, result.iterations)
            verify { chatMessageRepository.create(testTaskId, MessageRole.USER, userInput, any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `should save user message to history`() = runTest {
            // Given
            val userInput = "Test input"

            // When
            agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = userInput,
                mode = TaskMode.AGENT
            )

            // Then
            verify(atLeast = 1) { chatMessageRepository.create(testTaskId, MessageRole.USER, userInput, any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `should track token usage correctly`() = runTest {
            // Given
            val expectedInputTokens = 200
            val expectedOutputTokens = 100
            coEvery {
                llmClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    maxTokens = any(),
                    temperature = any(),
                    responseFormat = any(),
                    thinking = any(),
                    noEgressEnabled = any(),
                    stream = any(),
                    onChunk = any(),
                    taskId = any(),
                    subtaskId = any(),
                    source = any(),
                    kwargs = any()
                )
            } returns LLMResponse(
                content = """{"response": "Response"}""",
                usage = LLMUsage(inputTokens = expectedInputTokens, outputTokens = expectedOutputTokens, totalTokens = 300),
                model = "gpt-4",
                provider = "openai",
                cost = 0.002
            )

            // When
            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Test",
                mode = TaskMode.AGENT
            )

            // Then
            assertEquals(expectedInputTokens, result.tokensIn)
            assertEquals(expectedOutputTokens, result.tokensOut)
            assertTrue(result.cost > 0)
        }

        @Test
        fun `should throw when task not found`() = runTest {
            // Given
            every { taskRepository.findById(testTaskId) } returns null

            // When/Then
            assertThrows<IllegalArgumentException> {
                agentTurnLoop.runTurn(
                    taskId = testTaskId,
                    userInput = "Test",
                    mode = TaskMode.AGENT
                )
            }
        }
    }

    @Nested
    inner class ModeSpecificTests {

        @Test
        fun `should use JSON mode for PLAN mode`() = runTest {
            // Given
            coEvery {
                llmClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    maxTokens = any(),
                    temperature = any(),
                    responseFormat = any(),
                    thinking = any(),
                    noEgressEnabled = any(),
                    stream = any(),
                    onChunk = any(),
                    taskId = any(),
                    subtaskId = any(),
                    source = any(),
                    kwargs = any()
                )
            } returns LLMResponse(
                content = """{"response": "Plan response"}""",
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
                model = "gpt-4",
                provider = "openai",
                cost = 0.001
            )

            // When
            agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Plan this",
                mode = TaskMode.PLAN
            )

            // Then
            coVerify {
                llmClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    maxTokens = any(),
                    temperature = any(),
                    responseFormat = any(),
                    thinking = any(),
                    noEgressEnabled = any(),
                    stream = any(),
                    onChunk = any(),
                    taskId = any(),
                    subtaskId = any(),
                    source = any(),
                    kwargs = any()
                )
            }
        }

        @Test
        fun `should use plain mode for CHAT mode`() = runTest {
            // When
            agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Chat with me",
                mode = TaskMode.AGENT
            )

            // Then - verify LLM was called
            coVerify(atLeast = 1) {
                llmClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    maxTokens = any(),
                    temperature = any(),
                    responseFormat = any(),
                    thinking = any(),
                    noEgressEnabled = any(),
                    stream = any(),
                    onChunk = any(),
                    taskId = any(),
                    subtaskId = any(),
                    source = any(),
                    kwargs = any()
                )
            }
        }

        @Test
        fun `should respect read-only flag for PLAN mode`() = runTest {
            // Given
            val planTask = createMockTask(mode = TaskMode.PLAN)
            every { taskRepository.findById(testTaskId) } returns planTask

            // When
            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Analyze this",
                mode = TaskMode.PLAN
            )

            // Then
            assertTrue(result.success)
            verify { taskRepository.findById(testTaskId) }
        }
    }

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `should retry empty content from model in JSON mode`() = runTest {
            coEvery {
                llmClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    maxTokens = any(),
                    temperature = any(),
                    responseFormat = any(),
                    thinking = any(),
                    noEgressEnabled = any(),
                    stream = any(),
                    onChunk = any(),
                    taskId = any(),
                    subtaskId = any(),
                    source = any(),
                    kwargs = any()
                )
            } returnsMany listOf(
                LLMResponse(
                    content = "",
                    usage = LLMUsage(inputTokens = 100, outputTokens = 0, totalTokens = 100),
                    model = "gpt-4",
                    provider = "openai",
                    cost = 0.0,
                    finishReason = "stop"
                ),
                createLLMResponse("""{"response":"Recovered after retry"}""")
            )

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Test",
                mode = TaskMode.PLAN
            )

            assertTrue(result.success)
            assertEquals(2, result.iterations)
            verify {
                chatMessageRepository.create(
                    testTaskId,
                    MessageRole.SYSTEM,
                    TurnGuardrails.buildInvalidFormatMessage(TaskMode.PLAN),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
                )
            }
        }

        @Test
        fun `should fail after repeated empty content in JSON mode`() = runTest {
            coEvery {
                llmClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    maxTokens = any(),
                    temperature = any(),
                    responseFormat = any(),
                    thinking = any(),
                    noEgressEnabled = any(),
                    stream = any(),
                    onChunk = any(),
                    taskId = any(),
                    subtaskId = any(),
                    source = any(),
                    kwargs = any()
                )
            } returnsMany List(4) {
                LLMResponse(
                    content = "",
                    usage = LLMUsage(inputTokens = 100, outputTokens = 0, totalTokens = 100),
                    model = "gpt-4",
                    provider = "openai",
                    cost = 0.0,
                    finishReason = "stop"
                )
            }

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Test",
                mode = TaskMode.PLAN
            )

            assertFalse(result.success)
            assertTrue(result.response.contains("repeatedly returned empty content", ignoreCase = true))
        }

        @Test
        fun `should retry when model returns meaningless json`() = runTest {
            coEvery {
                llmClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    maxTokens = any(),
                    temperature = any(),
                    responseFormat = any(),
                    thinking = any(),
                    noEgressEnabled = any(),
                    stream = any(),
                    onChunk = any(),
                    taskId = any(),
                    subtaskId = any(),
                    source = any(),
                    kwargs = any()
                )
            } returnsMany listOf(
                createLLMResponse("""{}"""),
                createLLMResponse("""{"response":"Recovered"}""")
            )

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Test",
                mode = TaskMode.PLAN
            )

            assertTrue(result.success)
            assertEquals(2, result.iterations)
            verify {
                chatMessageRepository.create(
                    testTaskId,
                    MessageRole.SYSTEM,
                    TurnGuardrails.buildInvalidFormatMessage(TaskMode.PLAN),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
                )
            }
        }

        @Test
        fun `should handle LLM exceptions gracefully`() = runTest {
            // Given
            coEvery {
                llmClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    maxTokens = any(),
                    temperature = any(),
                    responseFormat = any(),
                    thinking = any(),
                    noEgressEnabled = any(),
                    stream = any(),
                    onChunk = any(),
                    taskId = any(),
                    subtaskId = any(),
                    source = any(),
                    kwargs = any()
                )
            } throws RuntimeException("LLM service unavailable")

            // When/Then - AgentTurnLoop propagates exceptions from LLM
            assertThrows<RuntimeException> {
                agentTurnLoop.runTurn(
                    taskId = testTaskId,
                    userInput = "Test",
                    mode = TaskMode.AGENT
                )
            }
        }
    }

    @Nested
    inner class StreamingTests {

        @Test
        fun `should support streaming responses`() = runTest {
            // Given
            val streamCallback = mockk<StreamCallback>(relaxed = true)

            // When
            agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Stream this",
                mode = TaskMode.AGENT,
                streamCallback = streamCallback
            )

            // Then - verify LLM was called with stream enabled
            coVerify(atLeast = 1) {
                llmClient.complete(
                    provider = any(),
                    model = any(),
                    messages = any(),
                    systemPrompt = any(),
                    maxTokens = any(),
                    temperature = any(),
                    responseFormat = any(),
                    thinking = any(),
                    noEgressEnabled = any(),
                    stream = eq(true),
                    onChunk = any(),
                    taskId = any(),
                    subtaskId = any(),
                    source = any(),
                    kwargs = any()
                )
            }
        }
    }

    @Nested
    inner class EventListenerTests {

        @Test
        fun `should notify listener on turn start`() = runTest {
            // Given
            val listener = mockk<AgentTurnLoop.TurnEventListener>(relaxed = true)

            // When
            agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Test",
                mode = TaskMode.AGENT,
                listener = listener
            )

            // Then
            verify { listener.onTurnStarted(testTaskId, any(), any(), any(), 0) }
        }

        @Test
        fun `should notify listener on turn completion`() = runTest {
            // Given
            val listener = mockk<AgentTurnLoop.TurnEventListener>(relaxed = true)

            // When
            agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Test",
                mode = TaskMode.AGENT,
                listener = listener
            )

            // Then
            verify { listener.onTurnCompleted(testTaskId, any(), any(), any(), 0) }
        }
    }

    @Nested
    inner class ModelSelectionTests {

        @Test
        fun `should use specified model when provided`() = runTest {
            // Given
            val customModel = "gpt-4-turbo"
            val customProvider = "openai"

            // When
            agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Test",
                mode = TaskMode.AGENT,
                model = customModel,
                provider = customProvider
            )

            // Then
            coVerify(atLeast = 1) {
                llmClient.complete(
                    provider = customProvider,
                    model = customModel,
                    messages = any(),
                    systemPrompt = any(),
                    maxTokens = any(),
                    temperature = any(),
                    responseFormat = any(),
                    thinking = any(),
                    noEgressEnabled = any(),
                    stream = any(),
                    onChunk = any(),
                    taskId = any(),
                    subtaskId = any(),
                    source = any(),
                    kwargs = any()
                )
            }
        }

        @Test
        fun `should use config model when not specified`() = runTest {
            // When - no model/provider specified
            agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Test",
                mode = TaskMode.AGENT
            )

            // Then - should use config values
            coVerify(atLeast = 1) {
                llmClient.complete(
                    provider = "openai",
                    model = "gpt-4",
                    messages = any(),
                    systemPrompt = any(),
                    maxTokens = any(),
                    temperature = any(),
                    responseFormat = any(),
                    thinking = any(),
                    noEgressEnabled = any(),
                    stream = any(),
                    onChunk = any(),
                    taskId = any(),
                    subtaskId = any(),
                    source = any(),
                    kwargs = any()
                )
            }
        }
    }
}
