package pl.jclab.refio.core.services

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.services.turn.*
import pl.jclab.refio.core.tools.base.ToolRegistry
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for mid-execution user message handling in AgentTurnLoop.
 * Verifies that messages sent by the user during agent execution are:
 * 1. Detected via PendingUserMessageQueue.consumePending()
 * 2. Nudged to the LLM via system messages
 * 3. Cause the loop to continue instead of exiting (when LLM gives text response)
 */
class MidExecutionMessageTest {

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
    private lateinit var pendingUserMessageQueue: PendingUserMessageQueue

    private val testTaskId = "task-mid-exec"
    private val testProjectId = "project-mid-exec"

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
        pendingUserMessageQueue = mockk(relaxed = true)

        every { promptsService.getSystemPrompt(any(), any()) } returns "System prompt"
        every { configService.getModel(any(), any()) } returns Pair("gpt-4", "openai")
        every { configService.getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }

        every { taskRepository.findById(testTaskId) } returns createMockTask()
        every { chatMessageRepository.create(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns createMockMessage()
        every { chatMessageRepository.findByTaskId(any()) } returns emptyList()
        every { contextService.collectAllUserContextRefs(any()) } returns emptyList()

        every { toolRegistry.getTool(any()) } returns null
        every { toolRegistry.getAllTools() } returns emptyList()
        every { toolPermissionsService.getPermission(any(), any(), any()) } returns PermissionLevel.ON
        every { configService.getTyped(pl.jclab.refio.core.config.ConfigKeys.JSON_THINKING_XML_TAGS, any()) } returns emptyList<String>()

        // Default: no pending messages
        every { pendingUserMessageQueue.consumePending(any()) } returns false
    }

    private fun buildAgentTurnLoop(): AgentTurnLoop {
        val tokenEstimator = PromptTokenEstimator()

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
            getJsonThinkingXmlTags = { configService.getTyped(pl.jclab.refio.core.config.ConfigKeys.JSON_THINKING_XML_TAGS, it) }
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
            workingMemoryIntegration = null,
            pendingUserMessageQueue = pendingUserMessageQueue
        )
    }

    private fun createMockTask() = Task(
        id = testTaskId, name = "Test Task", mode = TaskMode.AGENT,
        status = TaskStatus.RUNNING, readOnly = false, pinned = false,
        executionMode = ExecutionMode.INTERACTIVE, requiresPlanApproval = false,
        planApproved = false, uiState = null, coreApiVersion = "1.0",
        projectId = testProjectId, projectPath = "/test/project",
        rate = null, tokensIn = 0, tokensOut = 0, costUsd = 0.0,
        createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
    )

    private fun createMockMessage() = ChatMessage(
        id = "msg-1", taskId = testTaskId, role = MessageRole.USER,
        content = "Test message", thinking = null, metadata = null,
        toolCalls = null, toolCallId = null, isSummarized = false,
        rawOutput = null, tokensIn = null, tokensOut = null,
        cost = null, createdAt = System.currentTimeMillis()
    )

    private fun llmTextResponse(text: String = "Done") = LLMResponse(
        content = """{"response": "$text"}""",
        usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
        model = "gpt-4", provider = "openai", cost = 0.001
    )

    private fun llmToolCallResponse(toolName: String = "read_file", args: String = """{"path":"test.kt"}""") = LLMResponse(
        content = """{"actions": [{"tool": "$toolName", "args": $args}]}""",
        usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
        model = "gpt-4", provider = "openai", cost = 0.001
    )

    @Nested
    inner class TextResponseWithPendingMessage {

        @Test
        fun `should continue loop when pending message exists on text response`() = runTest {
            val agentTurnLoop = buildAgentTurnLoop()

            // First call: LLM gives text response (would normally exit)
            // But pending message exists → loop continues
            // Second call: LLM gives text response, no pending → exit
            var callCount = 0
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(),
                    systemPrompt = any(), maxTokens = any(), temperature = any(),
                    responseFormat = any(), thinking = any(), noEgressEnabled = any(),
                    stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } answers {
                callCount++
                llmTextResponse(if (callCount == 1) "Initial done" else "Addressed user message")
            }

            // First consumePending → true (pending message), second → false
            every { pendingUserMessageQueue.consumePending(testTaskId) } returnsMany listOf(true, false)

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Do something",
                mode = TaskMode.AGENT
            )

            // Should have 2 iterations (continued because of pending message)
            assertTrue(result.success)
            assertEquals(2, result.iterations)

            // Verify system nudge was created
            verify {
                chatMessageRepository.create(
                    taskId = testTaskId,
                    role = MessageRole.SYSTEM,
                    content = match { it.contains("New user message above") },
                    toolCalls = isNull()
                )
            }
        }

        @Test
        fun `should exit normally when no pending messages on text response`() = runTest {
            val agentTurnLoop = buildAgentTurnLoop()

            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(),
                    systemPrompt = any(), maxTokens = any(), temperature = any(),
                    responseFormat = any(), thinking = any(), noEgressEnabled = any(),
                    stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } returns llmTextResponse("All done")

            every { pendingUserMessageQueue.consumePending(testTaskId) } returns false

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Do something",
                mode = TaskMode.AGENT
            )

            assertTrue(result.success)
            assertEquals(1, result.iterations)

            // No system nudge
            verify(exactly = 0) {
                chatMessageRepository.create(
                    taskId = testTaskId,
                    role = MessageRole.SYSTEM,
                    content = match { it.contains("New user message above") },
                    toolCalls = any()
                )
            }
        }
    }

    @Nested
    inner class ToolExecutionWithPendingMessage {

        @Test
        fun `should inject nudge after tool execution when pending message exists`() = runTest {
            val agentTurnLoop = buildAgentTurnLoop()

            // Iteration 1: tool call, pending message → nudge injected
            // Iteration 2: text response, no pending → exit
            var callCount = 0
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(),
                    systemPrompt = any(), maxTokens = any(), temperature = any(),
                    responseFormat = any(), thinking = any(), noEgressEnabled = any(),
                    stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } answers {
                callCount++
                if (callCount == 1) llmToolCallResponse() else llmTextResponse("Done with user feedback")
            }

            // Set up tool execution mock
            every { toolRegistry.getTool("read_file") } returns mockk(relaxed = true)
            every { toolRegistry.toSubtaskKind("read_file") } returns SubtaskKind.PLAN_STEP
            every { subtaskRepository.create(any(), any(), any(), any(), any(), any()) } returns mockk(relaxed = true)
            every { subtaskRepository.getMaxOrderIndex(any()) } returns 0
            coEvery { toolExecutor.executeTool(any(), any()) } returns pl.jclab.refio.core.tools.base.ToolResult(success = true, output = "file content")
            coEvery { toolResultSummarizer.summarizeToolResult(any(), any(), any()) } returns ToolResultSummary("summary", false, 0, 0, 0.0)

            // First consumePending → true (after tool execution), second → false
            every { pendingUserMessageQueue.consumePending(testTaskId) } returnsMany listOf(true, false)

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Read a file",
                mode = TaskMode.AGENT
            )

            assertTrue(result.success)
            assertEquals(2, result.iterations)

            // Verify system nudge was created after tool execution
            verify(atLeast = 1) {
                chatMessageRepository.create(
                    taskId = testTaskId,
                    role = MessageRole.SYSTEM,
                    content = match { it.contains("New user message above") },
                    toolCalls = isNull()
                )
            }
        }
    }

    @Nested
    inner class NoPendingUserMessageQueue {

        @Test
        fun `should work normally without pendingUserMessageQueue`() = runTest {
            // Build without pendingUserMessageQueue (null)
            val tokenEstimator = PromptTokenEstimator()
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
                getJsonThinkingXmlTags = { configService.getTyped(pl.jclab.refio.core.config.ConfigKeys.JSON_THINKING_XML_TAGS, it) }
            )
            val turnToolExecutor = TurnToolExecutor(
                toolExecutor = toolExecutor,
                toolRegistry = toolRegistry,
                subtaskRepository = subtaskRepository,
                toolResultSummarizer = toolResultSummarizer,
                snapshotService = null,
                workingMemoryIntegration = null
            )
            val agentTurnLoop = AgentTurnLoop(
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
                turnLLMCaller = TurnLLMCaller(llmClient, configService),
                turnResponseProcessor = TurnResponseProcessor(subtaskRepository, toolRegistry, toolDescriptionBuilder),
                turnFinalizer = TurnFinalizer(chatMessageRepository),
                turnSubagentValidator = TurnSubagentValidator(3),
                tokenEstimator = tokenEstimator,
                pendingUserMessageQueue = null  // explicitly null
            )

            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(),
                    systemPrompt = any(), maxTokens = any(), temperature = any(),
                    responseFormat = any(), thinking = any(), noEgressEnabled = any(),
                    stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } returns llmTextResponse("Hello")

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Hi",
                mode = TaskMode.AGENT
            )

            assertTrue(result.success)
            assertEquals(1, result.iterations)
        }
    }
}
