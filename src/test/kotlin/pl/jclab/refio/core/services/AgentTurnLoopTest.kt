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

        agentTurnLoop = buildAgentTurnLoop(NoopTaskVerifier())
    }

    private fun buildAgentTurnLoop(taskVerifier: TaskVerifier): AgentTurnLoop {
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

        return AgentTurnLoop(
            llmClient = llmClient,
            chatMessageRepository = chatMessageRepository,
            taskRepository = taskRepository,
            subtaskRepository = subtaskRepository,
            configService = configService,
            toolRegistry = toolRegistry,
            toolDescriptionBuilder = toolDescriptionBuilder,
            taskVerifier = taskVerifier,
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

    private fun createMockSubtask(
        id: String,
        orderIndex: Int,
        kind: SubtaskKind,
        status: TaskStatus,
        description: String,
        paramsJson: String? = null,
        result: String? = null,
        summary: String? = null,
        errorMessage: String? = null
    ) = Subtask(
        id = id,
        taskId = testTaskId,
        orderIndex = orderIndex,
        kind = kind,
        status = status,
        description = description,
        paramsJson = paramsJson,
        stepPlanJson = null,
        summary = summary,
        requiresApproval = false,
        approvalStatus = ApprovalStatus.NOT_REQUIRED,
        approvedAt = null,
        result = result,
        errorMessage = errorMessage,
        errorStacktrace = null,
        llmModel = null,
        llmProvider = null,
        inputTokens = 0,
        outputTokens = 0,
        costUsd = 0.0,
        latencyMs = 0,
        snapshotIdBeforeWrite = null,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        startedAt = null,
        completedAt = null
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
    inner class TaskVerificationIntegrationTests {

        @Test
        fun `should continue after false completion claim when write tool already ran`() = runTest {
            val messages = mutableListOf<ChatMessage>()
            val subtasks = linkedMapOf<String, Subtask>()
            var messageCounter = 0
            var subtaskCounter = 0

            val advanceTool = mockk<pl.jclab.refio.core.tools.base.Tool>(relaxed = true) {
                every { name } returns "advance_code_editing"
                every { mode } returns pl.jclab.refio.core.tools.base.ToolMode.WRITE
            }
            val readFileTool = mockk<pl.jclab.refio.core.tools.base.Tool>(relaxed = true) {
                every { name } returns "read_file"
                every { mode } returns pl.jclab.refio.core.tools.base.ToolMode.READ_ONLY
            }
            val fileSearchTool = mockk<pl.jclab.refio.core.tools.base.Tool>(relaxed = true) {
                every { name } returns "file_search"
                every { mode } returns pl.jclab.refio.core.tools.base.ToolMode.READ_ONLY
            }

            every { toolRegistry.getTool("advance_code_editing") } returns advanceTool
            every { toolRegistry.getTool("read_file") } returns readFileTool
            every { toolRegistry.getTool("file_search") } returns fileSearchTool
            every { toolRegistry.toSubtaskKind(any()) } answers {
                SubtaskKind.valueOf(firstArg<String>().uppercase())
            }

            every { subtaskRepository.getMaxOrderIndex(testTaskId) } answers {
                subtasks.values.maxOfOrNull { it.orderIndex } ?: -1
            }
            every {
                subtaskRepository.create(
                    taskId = any(),
                    orderIndex = any(),
                    kind = any(),
                    description = any(),
                    paramsJson = any(),
                    stepPlanJson = any(),
                    requiresApproval = any(),
                    status = any(),
                    llmModel = any(),
                    llmProvider = any()
                )
            } answers {
                val subtask = createMockSubtask(
                    id = "subtask-${++subtaskCounter}",
                    orderIndex = secondArg(),
                    kind = thirdArg(),
                    status = arg(7),
                    description = arg(3),
                    paramsJson = arg(4)
                )
                subtasks[subtask.id] = subtask
                subtask
            }
            every { subtaskRepository.findById(any()) } answers { subtasks[firstArg()] }
            every { subtaskRepository.updateStatus(any(), any()) } answers {
                val id = firstArg<String>()
                val status = secondArg<TaskStatus>()
                val current = subtasks.getValue(id)
                val updated = current.copy(status = status)
                subtasks[id] = updated
                updated
            }
            every {
                subtaskRepository.updateResult(
                    id = any(),
                    result = any(),
                    summary = any(),
                    errorMessage = any(),
                    errorStacktrace = any()
                )
            } answers {
                val id = firstArg<String>()
                val updated = subtasks.getValue(id).copy(
                    result = secondArg(),
                    summary = thirdArg(),
                    errorMessage = arg(3),
                    errorStacktrace = arg(4)
                )
                subtasks[id] = updated
                updated
            }

            every { chatMessageRepository.findByTaskId(testTaskId) } answers { messages.toList() }
            every {
                chatMessageRepository.create(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
                )
            } answers {
                val message = ChatMessage(
                    id = "msg-${++messageCounter}",
                    taskId = firstArg(),
                    role = secondArg(),
                    content = thirdArg(),
                    thinking = arg(3),
                    metadata = arg(4),
                    toolCalls = arg(5),
                    toolCallId = arg(6),
                    isSummarized = arg(7),
                    rawOutput = arg(8),
                    tokensIn = null,
                    tokensOut = null,
                    cost = null,
                    createdAt = System.currentTimeMillis()
                )
                messages += message
                message
            }
            every {
                chatMessageRepository.create(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
                )
            } answers {
                val message = ChatMessage(
                    id = "msg-${++messageCounter}",
                    taskId = firstArg(),
                    role = secondArg(),
                    content = thirdArg(),
                    thinking = arg(3),
                    metadata = arg(4),
                    toolCalls = arg(5),
                    toolCallId = arg(6),
                    isSummarized = arg(7),
                    rawOutput = arg(8),
                    tokensIn = arg(9),
                    tokensOut = arg(10),
                    cost = arg(11),
                    createdAt = System.currentTimeMillis()
                )
                messages += message
                message
            }
            every {
                chatMessageRepository.createToolResult(
                    taskId = any(),
                    toolCallId = any(),
                    result = any(),
                    isSummarized = any(),
                    rawOutput = any(),
                    metadata = any()
                )
            } answers {
                val message = ChatMessage(
                    id = "msg-${++messageCounter}",
                    taskId = firstArg(),
                    role = MessageRole.TOOL,
                    content = thirdArg(),
                    thinking = null,
                    metadata = arg(5),
                    toolCalls = null,
                    toolCallId = secondArg(),
                    isSummarized = arg(3),
                    rawOutput = arg(4),
                    tokensIn = null,
                    tokensOut = null,
                    cost = null,
                    createdAt = System.currentTimeMillis()
                )
                messages += message
                message
            }

            every { configService.shouldVerifyTask(testTaskId, any(), any()) } answers {
                thirdArg<Int>() > 0 || secondArg<Int>() >= 5
            }

            coEvery { toolResultSummarizer.summarizeToolResult(any(), any(), any()) } answers {
                ToolResultSummary(
                    summary = secondArg(),
                    wasSummarized = false,
                    tokensIn = 0,
                    tokensOut = 0,
                    cost = 0.0
                )
            }

            coEvery { toolExecutor.executeTool(any(), any()) } answers {
                val toolCall = firstArg<pl.jclab.refio.core.services.ToolCall>()
                when (toolCall.name) {
                    "advance_code_editing" -> ToolResult(success = true, output = "index.html updated", filesChanged = listOf("index.html"))
                    "read_file" -> ToolResult(success = true, output = "<html>broken links still present</html>")
                    "file_search" -> ToolResult(success = true, output = "snake_ollama_qwen3-coder-next_q4_K_M_01.html\npong_ollama_qwen3-coder-next_q4_K_M_01.html")
                    else -> error("Unexpected tool: ${toolCall.name}")
                }
            }

            val verifier = LlmTaskVerifier(
                llmClient = llmClient,
                configService = configService,
                chatMessageRepository = chatMessageRepository
            )
            val verifiedTurnLoop = buildAgentTurnLoop(verifier)

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
                    source = eq("TaskVerifier"),
                    kwargs = any()
                )
            } returnsMany listOf(
                createLLMResponse("""{"is_complete":false,"reason":"The assistant summary is contradicted by recent tool evidence.","suggested_actions":["Fix the index.html links using the real filenames."]}"""),
                createLLMResponse("""{"is_complete":true,"reason":"The follow-up response is acceptable.","suggested_actions":[]}""")
            )

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
                    source = eq("AgentTurnLoop"),
                    kwargs = any()
                )
            } returnsMany listOf(
                createLLMResponse(
                    """{"actions":[{"tool":"advance_code_editing","arguments":{"path":"index.html","edit_description":"Replace placeholders with real files."}}],"response":"Updating index.html.","intent":"implementation"}"""
                ),
                createLLMResponse(
                    """{"actions":[{"tool":"read_file","arguments":{"path":"index.html"}},{"tool":"file_search","arguments":{"pattern":"*.html","limit":200}}],"response":"Checking current file and available HTML files.","intent":"implementation"}"""
                ),
                createLLMResponse(
                    """{"actions":[],"response":"Finished. All links were fixed.","intent":"analysis"}"""
                ),
                createLLMResponse(
                    """{"actions":[{"tool":"advance_code_editing","arguments":{"path":"index.html","edit_description":"Fix remaining broken links based on actual files."}}],"response":"Applying the missing link fixes.","intent":"implementation"}"""
                ),
                createLLMResponse(
                    """{"actions":[],"response":"Final update applied.","intent":"analysis"}"""
                )
            )

            val result = verifiedTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Uzupełnij mi plik o rzeczywiste nazwy plików bo opisane prowadzą do błęd 404. 2. Dodaj nowe pozycje",
                mode = TaskMode.AGENT
            )

            assertTrue(result.success)
            assertEquals(5, result.iterations)
            coVerify(atLeast = 2) {
                toolExecutor.executeTool(
                    match { it.name == "advance_code_editing" },
                    testTaskId
                )
            }
            verify {
                configService.shouldVerifyTask(testTaskId, 3, 1)
            }
            assertTrue(messages.any { it.role == MessageRole.SYSTEM && it.content.contains("Task verification failed:") })
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
