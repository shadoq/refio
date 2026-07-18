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
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.NativeToolsFallbackTracker
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.services.turn.GuardianContext
import pl.jclab.refio.core.services.turn.GuardianDecision
import pl.jclab.refio.core.services.turn.GuardianRegistry
import pl.jclab.refio.core.services.turn.TurnCompletionGuardian
import pl.jclab.refio.core.services.turn.TurnFinalizer
import pl.jclab.refio.core.services.turn.TurnGuardrails
import pl.jclab.refio.core.services.turn.TurnLLMCaller
import pl.jclab.refio.core.services.turn.TurnPromptBuilder
import pl.jclab.refio.core.services.turn.TurnResponseProcessor
import pl.jclab.refio.core.services.turn.TurnSubagentValidator
import pl.jclab.refio.core.services.turn.ToolCallParser
import pl.jclab.refio.core.services.turn.TurnToolExecutor
import pl.jclab.refio.core.config.ConfigKey
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        // NativeToolsFallbackTracker is a process-global singleton; a test that marks a model as
        // a fallback (native→JSON) would otherwise leak that state into later tests and flip their
        // native-vs-JSON path. Reset it so each test starts from a known "no fallbacks" baseline.
        NativeToolsFallbackTracker.clear()
        // Verification trackers are process-global singletons keyed by taskId; reset so a
        // verification test cannot leak its recorded summary/marker into later tests.
        pl.jclab.refio.core.debug.TurnVerificationTracker.reset()
        pl.jclab.refio.core.debug.TurnFailureMarkerTracker.reset()
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
        every { configService.getTyped(any<ConfigKey<Any>>(), any()) } answers { firstArg<ConfigKey<Any>>().default }

        val mockTask = createMockTask()
        every { taskRepository.findById(testTaskId) } returns mockTask
        every { chatMessageRepository.create(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns createMockMessage()
        every { chatMessageRepository.findByTaskId(any()) } returns emptyList()
        every { contextService.collectAllUserContextRefs(any()) } returns emptyList()

        every { toolRegistry.getTool(any()) } returns null
        every { toolRegistry.getAllTools() } returns emptyList()
        every { toolPermissionsService.getPermission(any(), any(), any()) } returns PermissionLevel.ON
        every { configService.getTyped(pl.jclab.refio.core.config.ConfigKeys.JSON_THINKING_XML_TAGS, any()) } returns emptyList<String>()

        agentTurnLoop = buildAgentTurnLoop(NoopTaskVerifier())
    }

    private fun buildAgentTurnLoop(
        taskVerifier: TaskVerifier,
        completionGuardians: GuardianRegistry = GuardianRegistry(),
        turnVerifier: pl.jclab.refio.core.services.turn.TurnVerifier? = null
    ): AgentTurnLoop {
        val tokenEstimator = pl.jclab.refio.core.services.PromptTokenEstimator()

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
            getJsonThinkingXmlTags = { taskId -> configService.getTyped(pl.jclab.refio.core.config.ConfigKeys.JSON_THINKING_XML_TAGS, taskId) }
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
            toolPermissionsService = toolPermissionsService,
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
            workingMemoryIntegration = null,
            completionGuardians = completionGuardians,
            turnVerifier = turnVerifier
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
    inner class ContinueTurnRagPauseTests {

        @Test
        fun `continueTurn marks the agent turn active so RAG indexing yields during resume`() = runTest {
            // A turn paused for tool approval resumes via continueTurn. runTurn wraps the loop in
            // beginAgentTurn/endAgentTurn so background RAG indexing yields the SQLite WAL writer-lock
            // for the turn's duration; continueTurn must do the same. Without it, an interactive
            // resume-after-approval lets RAG grab the writer-lock and stall tool subtask-status writes.
            var activeDuringLoop = false
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } answers {
                activeDuringLoop = pl.jclab.refio.core.services.monitoring.GlobalMetrics.isAgentTurnActive()
                LLMResponse(
                    content = """{"response": "resumed"}""",
                    usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
                    model = "gpt-4", provider = "openai", cost = 0.001
                )
            }

            val activeBefore = pl.jclab.refio.core.services.monitoring.GlobalMetrics.isAgentTurnActive()
            val result = agentTurnLoop.continueTurn(taskId = testTaskId, mode = TaskMode.AGENT)

            assertFalse(activeBefore, "baseline: no agent turn should be active before continueTurn")
            assertTrue(activeDuringLoop, "continueTurn must mark the agent turn active during the resumed loop")
            assertFalse(
                pl.jclab.refio.core.services.monitoring.GlobalMetrics.isAgentTurnActive(),
                "the begin/end count must be balanced back to inactive after continueTurn"
            )
            assertTrue(result.success, "the resumed turn should complete: ${result.response}")
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
        fun `should fail immediately on empty content in PLAN mode`() = runTest {
            // Nudge-retry loops were removed — an empty-content response now terminates
            // the turn with a direct error message. That's the tradeoff the simplification
            // pays: weaker models get less hand-holding, but the control flow stays clean.
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
                content = "",
                usage = LLMUsage(inputTokens = 100, outputTokens = 0, totalTokens = 100),
                model = "gpt-4",
                provider = "openai",
                cost = 0.0,
                finishReason = "stop"
            )

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Test",
                mode = TaskMode.PLAN
            )

            assertFalse(result.success)
            assertEquals(1, result.iterations)
            assertTrue(result.response.contains("empty content", ignoreCase = true))
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

    /**
     * Tests for the empty-content / nudge fixes from docs/0107-multiagent.md.
     *
     * Background: qwen3.5:35b on Ollama would emit empty `content` (the JSON envelope ended up in
     * the `thinking` field instead) and the loop would burn through all retries trying to nudge
     * the model back into format. These tests pin down the recovery behaviours we now rely on.
     */
    @Nested
    inner class EmptyContentRecoveryAndNudgeTests {

        @Test
        fun `should recover when JSON envelope arrives in thinking field instead of content`() = runTest {
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } returns LLMResponse(
                content = "",
                thinking = """{"response":"Recovered from thinking","intent":"response"}""",
                usage = LLMUsage(inputTokens = 100, outputTokens = 25, totalTokens = 125),
                model = "qwen3.5:35b",
                provider = "ollama",
                cost = 0.0,
                finishReason = "stop"
            )

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Test thinking recovery",
                mode = TaskMode.PLAN
            )

            // Should succeed in a single iteration: empty content but thinking carried valid JSON.
            assertTrue(result.success, "expected recovery from thinking field, got: ${result.response}")
            assertEquals(1, result.iterations)
        }

        @Test
        fun `should retry when content is empty in JSON mode and then succeed`() = runTest {
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } returnsMany listOf(
                LLMResponse(
                    content = "",
                    usage = LLMUsage(inputTokens = 100, outputTokens = 0, totalTokens = 100),
                    model = "qwen3.5:122b",
                    provider = "ollama",
                    cost = 0.0,
                    finishReason = "stop"
                ),
                createLLMResponse("""{"actions":[],"response":"Recovered after empty content","intent":"implementation"}""")
            )

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Continue",
                mode = TaskMode.AGENT
            )

            assertTrue(result.success, "expected empty-content retry recovery, got: ${result.response}")
            assertEquals(2, result.iterations)
            assertEquals("Recovered after empty content", result.response)
            coVerify(exactly = 2) {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            }
            verify(atLeast = 1) {
                chatMessageRepository.create(
                    testTaskId,
                    MessageRole.SYSTEM,
                    match {
                        it.contains("empty content in structured JSON mode") &&
                            it.contains("Generate the full JSON envelope again from scratch")
                    },
                    any(), any(), any(), any(), any(), any()
                )
            }
        }

        @Test
        fun `should retry when fenced json envelope is incomplete and then succeed`() = runTest {
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } returnsMany listOf(
                createLLMResponse(
                    """
                    ```json
                    {
                      "actions": [
                        {"tool":"http_request","args":{"url":"https://hub.ag3nts.org/verify"}}
                      ],
                      response:"Retry me",
                      "intent":"implementation"
                    """.trimIndent()
                ),
                createLLMResponse("""{"actions":[],"response":"Recovered JSON","intent":"implementation"}""")
            )

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Continue",
                mode = TaskMode.AGENT
            )

            assertTrue(result.success, "expected JSON retry recovery, got: ${result.response}")
            assertEquals(2, result.iterations)
            assertEquals("Recovered JSON", result.response)
            coVerify(exactly = 2) {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            }
            verify(atLeast = 1) {
                chatMessageRepository.create(
                    testTaskId,
                    MessageRole.SYSTEM,
                    match { it.contains("incomplete JSON") && it.contains("Generate the full JSON envelope again from scratch") },
                    any(), any(), any(), any(), any(), any()
                )
            }
        }

        @Test
        fun `plain-text answer in JSON mode is surfaced as INCOMPLETE, not discarded as FAILED`() = runTest {
            // A model that ignores the JSON-envelope contract and just answers in prose used to get
            // its answer thrown away and replaced with a generic "wrong format" error (FAILED).
            // The real deliverable must be surfaced; the turn is INCOMPLETE (delivered text, but
            // not via the structured workflow), not FAILED.
            // Distinct prose each iteration so the cross-iteration repetition guard doesn't fire
            // first; this drives the bounded nudge counter to exhaustion (2 nudges) and into the
            // format hard-fail branch, where the answer must now be preserved.
            val finalProse = "Final answer: Anna, Bob, Cecylia and Dawid used transport."
            fun prose(text: String) = LLMResponse(
                content = text,
                usage = LLMUsage(inputTokens = 100, outputTokens = 20, totalTokens = 120),
                model = "qwen3.5:122b",
                provider = "ollama",
                cost = 0.0,
                finishReason = "stop"
            )
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } returnsMany listOf(
                prose("Let me think about who used transport here."),
                prose("Looking at the data, several people used transport."),
                prose(finalProse)
            )

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Who used transport?",
                mode = TaskMode.AGENT
            )

            assertFalse(result.success, "format lapse is not a clean success")
            assertTrue(result.incomplete, "prose answer in envelope mode should mark the turn INCOMPLETE")
            assertEquals(finalProse, result.response, "the model's actual answer must be preserved, not discarded")
        }

        @Test
        fun `format hard-fail after a write already landed finalizes SUCCESS, not INCOMPLETE`() = runTest {
            // e2e regression (qwen3-coder:30b on a constant-change task): the model edited the file
            // correctly (a WRITE tool ran), then emitted a malformed prose "double-check" grep that
            // never parsed as a tool call. After two format nudges the hard-fail used to mark the turn
            // INCOMPLETE — reporting a completed edit as a failure purely on the trailing format lapse.
            // With a deliverable already on disk (writeToolsExecutedInTurn>0), the turn must finalize
            // SUCCESS and surface the prose; only a turn that produced NO deliverable stays INCOMPLETE.
            val advanceTool = mockk<pl.jclab.refio.core.tools.base.Tool>(relaxed = true) {
                every { name } returns "advance_code_editing"
                every { mode } returns pl.jclab.refio.core.tools.base.ToolMode.WRITE
            }
            every { toolRegistry.getTool("advance_code_editing") } returns advanceTool
            coEvery { toolExecutor.executeTool(any(), any()) } returns
                ToolResult(success = true, output = "edited Config.kt")

            fun prose(text: String) = LLMResponse(
                content = text,
                usage = LLMUsage(inputTokens = 100, outputTokens = 20, totalTokens = 120),
                model = "qwen3-coder:30b", provider = "ollama", cost = 0.0, finishReason = "stop"
            )
            val finalProse = "I've confirmed MAX_RETRIES is now 5. The change is complete."
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } returnsMany listOf(
                // iter 1: a real WRITE tool call — the deliverable lands (writeToolsExecutedInTurn>0).
                createLLMResponse("""{"response":"editing the constant","actions":[{"tool":"advance_code_editing","arguments":{"path":"Config.kt","instructions":"set MAX_RETRIES to 5"}}]}"""),
                // iters 2-4: malformed prose "double-check" that never parses as a tool call → two
                // format nudges → hard-fail. Distinct each time so the repetition guard does not fire first.
                prose("Let me double-check by searching for other occurrences. [TOOL] grep_search pattern=MAX_RETRIES"),
                prose("Let me verify once more across the codebase. [TOOL] grep_search pattern=MAX_RETRIES path=."),
                prose(finalProse)
            )

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Set MAX_RETRIES to 5",
                mode = TaskMode.AGENT
            )

            assertTrue(result.success, "a landed edit + trailing format lapse must finalize SUCCESS: ${result.response}")
            assertFalse(result.incomplete, "the deliverable was produced; the turn must not be INCOMPLETE")
        }

        @Test
        fun `a verification-tool loop after a real edit finalizes SUCCESS, not a loop failure`() = runTest {
            // e2e regression (qwen3-coder:30b / make-test-pass): the model fixed the code (a real edit),
            // then ran the SAME run_terminal_command verification 4x with byte-identical output → the
            // repetition guard aborted the turn as a FAILED loop, reporting a completed edit as failure.
            // A loop on an OPTIONAL verification tool after a real file edit landed must finalize SUCCESS.
            val editTool = mockk<pl.jclab.refio.core.tools.base.Tool>(relaxed = true) {
                every { name } returns "advance_code_editing"
                every { mode } returns pl.jclab.refio.core.tools.base.ToolMode.WRITE
            }
            // run_terminal_command is mode=WRITE in prod (for approval) but is an EXECUTION tool, so
            // isFileWriteTool() must exclude it — only the advance_code_editing edit counts as the deliverable.
            val cmdTool = mockk<pl.jclab.refio.core.tools.base.Tool>(relaxed = true) {
                every { name } returns "run_terminal_command"
                every { mode } returns pl.jclab.refio.core.tools.base.ToolMode.WRITE
            }
            every { toolRegistry.getTool("advance_code_editing") } returns editTool
            every { toolRegistry.getTool("run_terminal_command") } returns cmdTool
            coEvery { toolExecutor.executeTool(match { it.name == "advance_code_editing" }, any()) } returns
                ToolResult(success = true, output = "edited Calc.kt")
            // Byte-identical command output every time → repetition guard ABORT at the 4th identical call.
            coEvery { toolExecutor.executeTool(match { it.name == "run_terminal_command" }, any()) } returns
                ToolResult(success = true, output = "Compilation OK")

            val edit = createLLMResponse("""{"response":"fixing","actions":[{"tool":"advance_code_editing","arguments":{"path":"Calc.kt","instructions":"return a + b"}}]}""")
            val verify = createLLMResponse("""{"response":"verifying","actions":[{"tool":"run_terminal_command","arguments":{"command":"kotlinc src/Calc.kt"}}]}""")
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } returnsMany listOf(edit, verify, verify, verify, verify, verify)

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Fix add() and verify it compiles",
                mode = TaskMode.AGENT
            )

            assertTrue(result.success, "edit landed + verification loop must finalize SUCCESS: ${result.response}")
            assertFalse(result.incomplete, "the edit deliverable landed; not an INCOMPLETE abandonment")
            assertTrue(
                result.response.contains("verification", ignoreCase = true),
                "should note the stopped verification step, got: ${result.response}",
            )
        }

        @Test
        fun `should fail instead of finalizing success when incomplete json persists`() = runTest {
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } returnsMany listOf(
                createLLMResponse("""```json
{response:"one","intent":"implementation"
"""),
                createLLMResponse("""```json
{response:"two","intent":"implementation"
"""),
                createLLMResponse("""```json
{response:"three","intent":"implementation"
""")
            )

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Continue",
                mode = TaskMode.AGENT
            )

            assertFalse(result.success)
            assertTrue(result.response.contains("incomplete JSON envelope"))
            assertEquals(3, result.iterations)
            coVerify(exactly = 3) {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            }
        }

        @Test
        fun `a mkdir-only turn that then breaks format stays INCOMPLETE - a trivial command is not a file deliverable`() = runTest {
            // e2e regression (build-cli-todo-app, qwen3.5:122b): the model ran ONLY `mkdir -p tests`
            // and then stopped without writing any source file, yet the turn self-reported SUCCESS
            // because run_terminal_command is mode=WRITE. Execution tools (run_terminal_command /
            // run_code) produce NO file, so they must not satisfy the deliverable proxy - only a real
            // file edit/create does. Otherwise a scaffold-and-stall reports a completed deliverable.
            val cmdTool = mockk<pl.jclab.refio.core.tools.base.Tool>(relaxed = true) {
                every { name } returns "run_terminal_command"
                every { mode } returns pl.jclab.refio.core.tools.base.ToolMode.WRITE
            }
            every { toolRegistry.getTool("run_terminal_command") } returns cmdTool
            coEvery { toolExecutor.executeTool(match { it.name == "run_terminal_command" }, any()) } returns
                ToolResult(success = true, output = "created tests/")

            val mkdir = createLLMResponse("""{"response":"scaffolding the project","actions":[{"tool":"run_terminal_command","arguments":{"command":"mkdir -p tests"}}]}""")
            val broken = createLLMResponse("""```json
{response:"still working",
""")
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } returnsMany listOf(mkdir, broken, broken, broken, broken, broken)

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Build the todo app with app.py, store.py and tests",
                mode = TaskMode.AGENT
            )

            assertFalse(
                result.success,
                "mkdir alone is not a file deliverable; a format breakdown after it must not finalize SUCCESS: ${result.response}"
            )
        }

        // NOTE: the bounded nudge-retry machinery is intentionally still here. On an empty or
        // malformed structured response the loop injects a SYSTEM message telling the model to
        // regenerate the JSON envelope from scratch (the MessageRole.SYSTEM writes in
        // AgentTurnLoop's empty-content and broken-format branches), bounded to 2 nudges before
        // it fails loud. That path is covered by the sibling tests `should retry when content is
        // empty in JSON mode and then succeed`, `should retry when fenced json envelope is
        // incomplete and then succeed`, and `should fail when empty content persists after
        // retries in AGENT mode` (below).
        @Test
        fun `should fail when empty content persists after retries in AGENT mode`() = runTest {
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } returnsMany listOf(
                LLMResponse(
                    content = "",
                    usage = LLMUsage(inputTokens = 100, outputTokens = 0, totalTokens = 100),
                    model = "qwen3.5:122b",
                    provider = "ollama",
                    cost = 0.0,
                    finishReason = "stop"
                ),
                LLMResponse(
                    content = "",
                    usage = LLMUsage(inputTokens = 100, outputTokens = 0, totalTokens = 100),
                    model = "qwen3.5:122b",
                    provider = "ollama",
                    cost = 0.0,
                    finishReason = "stop"
                ),
                LLMResponse(
                    content = "",
                    usage = LLMUsage(inputTokens = 100, outputTokens = 0, totalTokens = 100),
                    model = "qwen3.5:122b",
                    provider = "ollama",
                    cost = 0.0,
                    finishReason = "stop"
                )
            )

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Continue",
                mode = TaskMode.AGENT
            )

            assertFalse(result.success)
            assertTrue(result.response.contains("empty content", ignoreCase = true))
            assertTrue(result.response.contains("could not recover after retrying", ignoreCase = true))
            assertEquals(3, result.iterations)
            coVerify(exactly = 3) {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            }
        }

        @Test
        fun `empty content give-up after a write already landed finalizes SUCCESS, not failure`() = runTest {
            // e2e regression (qwen3.5 on the 1260-run gate): a WRITE tool ran on iter 1 (the file
            // deliverable landed), then the model emitted empty JSON envelopes it could not recover
            // from. The empty-content GiveUp path used to report FAILURE unconditionally, so a
            // completed edit was recorded as a failed turn purely on the trailing sign-off lapse.
            // With a deliverable on disk the turn must finalize SUCCESS.
            val advanceTool = mockk<pl.jclab.refio.core.tools.base.Tool>(relaxed = true) {
                every { name } returns "advance_code_editing"
                every { mode } returns pl.jclab.refio.core.tools.base.ToolMode.WRITE
            }
            every { toolRegistry.getTool("advance_code_editing") } returns advanceTool
            coEvery { toolExecutor.executeTool(any(), any()) } returns
                ToolResult(success = true, output = "edited Config.kt")

            fun empty() = LLMResponse(
                content = "",
                usage = LLMUsage(inputTokens = 100, outputTokens = 0, totalTokens = 100),
                model = "qwen3.5:35b", provider = "ollama", cost = 0.0, finishReason = "stop"
            )
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } returnsMany listOf(
                // iter 1: a real WRITE tool call - the deliverable lands (writeToolsExecutedInTurn>0).
                createLLMResponse("""{"response":"editing the constant","actions":[{"tool":"advance_code_editing","arguments":{"path":"Config.kt","instructions":"set MAX_RETRIES to 5"}}]}"""),
                // iters 2-4: empty content -> nudge, nudge, then GiveUp (nudgeCount>=2).
                empty(), empty(), empty()
            )

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Set MAX_RETRIES to 5",
                mode = TaskMode.AGENT
            )

            assertTrue(result.success, "a landed edit + empty-envelope give-up must finalize SUCCESS: ${result.response}")
            assertFalse(result.incomplete, "the deliverable was produced; the turn must not be a failure")
            assertFalse(
                result.response.contains("could not recover", ignoreCase = true),
                "must not surface the give-up error text when a deliverable landed, got: ${result.response}",
            )
        }

        @Test
        fun `should fall back to JSON path when native tools return empty, then succeed`() = runTest {
            // Regression (benchmark sessions 00e9f6af / 3b672fbe): a model with native tool schemas
            // attached can return content="" + zero tool_calls (gemma4:26b's broken Ollama tool
            // template does this on every call; under NATIVE_TOOLS_MODE=ALWAYS that bypasses the
            // supportsFunctionCalling guard and the empty response killed the turn with
            // TURN_FAILED_NATIVE_EMPTY). The loop must retry ONCE on the JSON-envelope path — the
            // non-exception twin of NATIVE_TOOLS_PARSE_FALLBACK — and recover instead of failing.
            //
            // We activate native tools via the documented AUTO default + a model whose definition
            // has supportsFunctionCalling=true (gpt-5.5). That reaches the SAME empty→fallback
            // branch without stubbing NATIVE_TOOLS_MODE (re-stubbing the generic getTyped answer
            // does not reliably override the @BeforeEach one in this harness).
            every { toolRegistry.getToolSchemas(any(), any(), any()) } returns listOf(
                pl.jclab.refio.core.tools.base.ToolSchema("read_file", "Read a file", mapOf("type" to "object"))
            )

            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } returnsMany listOf(
                LLMResponse(
                    content = "",
                    usage = LLMUsage(inputTokens = 16614, outputTokens = 192, totalTokens = 16806),
                    model = "gpt-5.5",
                    provider = "openai",
                    cost = 0.0,
                    finishReason = "stop"
                ),
                createLLMResponse("""{"actions":[],"response":"Recovered via JSON path","intent":"analysis"}""")
            )

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId,
                userInput = "Build the page",
                mode = TaskMode.AGENT,
                model = "gpt-5.5",
                provider = "openai"
            )

            assertTrue(result.success, "expected native-empty → JSON fallback recovery, got: ${result.response}")
            // The fallback retries inside the same iteration's call loop (like the parse-error
            // fallback), so it must NOT advance the iteration counter.
            assertEquals(1, result.iterations)
            assertEquals("Recovered via JSON path", result.response)
            coVerify(exactly = 2) {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            }
        }

        @Test
        fun `guardian re-entry after a native no-call switches the retry to the JSON contract`() = runTest {
            // Symptom 3 (session 6ef58656): a weak model with native tools attached intermittently
            // narrates intent ("Let me explore the docs…") with ZERO native tool_calls. The turn treats
            // that prose as a legitimate final answer, so the NextSpeakerJudge guardian is the only thing
            // that re-enters — and re-entering on the SAME native channel that just failed tends to
            // reproduce the stall. The fix: when a guardian re-enters after a native-no-call, drop native
            // tools so the bounded re-entry retries on the JSON-in-text contract (which weak local models
            // often follow better), giving it a real chance to emit a tool call instead of more prose.
            every { toolRegistry.getToolSchemas(any(), any(), any()) } returns listOf(
                pl.jclab.refio.core.tools.base.ToolSchema("read_file", "Read a file", mapOf("type" to "object"))
            )

            val reenterOnce = object : TurnCompletionGuardian {
                override val name = "test_reenter_once"
                private var calls = 0
                override suspend fun check(context: GuardianContext): GuardianDecision {
                    calls++
                    return if (calls == 1) {
                        GuardianDecision.Reenter(nudge = "Finish the work with a tool call.", reason = "test stall")
                    } else {
                        GuardianDecision.Pass
                    }
                }
            }
            val loop = buildAgentTurnLoop(
                NoopTaskVerifier(),
                GuardianRegistry(listOf(reenterOnce), maxReentries = 3)
            )

            val kwargsPerCall = mutableListOf<Map<String, Any>>()
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = capture(kwargsPerCall)
                )
            } returnsMany listOf(
                // Call 1 — native channel active, but the model only narrates (no tool_calls).
                LLMResponse(
                    content = "Let me explore the docs directory.",
                    usage = LLMUsage(inputTokens = 1200, outputTokens = 14, totalTokens = 1214),
                    model = "gpt-5.5", provider = "openai", cost = 0.0, finishReason = "stop"
                ),
                // Call 2 — the re-entry; a clean JSON final envelope ends the turn.
                createLLMResponse("""{"actions":[],"response":"Explored the docs.","intent":"analysis"}""")
            )

            val result = loop.runTurn(
                taskId = testTaskId,
                userInput = "Document the project",
                mode = TaskMode.AGENT,
                model = "gpt-5.5",
                provider = "openai"
            )

            assertEquals(2, kwargsPerCall.size, "expected exactly the two turn calls (the guardian makes none)")
            assertTrue(
                kwargsPerCall[0].containsKey("native_tools"),
                "first call must use the native channel"
            )
            assertTrue(
                !kwargsPerCall[1].containsKey("native_tools"),
                "the guardian re-entry must drop native tools and retry on the JSON contract"
            )
            assertTrue(result.success, "the JSON re-entry should let the turn finish cleanly: ${result.response}")
        }

        @Test
        fun `a single JSON-envelope slip does not persistently demote a capable native model`() = runTest {
            // docs/0068 / R1: a model with verified native function-calling (gpt-5.5) occasionally
            // mirrors the {response,actions} envelope shown as a negative example in the prompt instead
            // of emitting native tool_calls. A ONE-strike persistent demotion (the old behavior) kicked
            // such a capable model off native for the rest of the session (and disk) on that single slip.
            // One slip followed by a proper native turn must NOT mark the model as a fallback.
            every { toolRegistry.hasTool("read_file") } returns true
            every { toolRegistry.getToolSchemas(any(), any(), any()) } returns listOf(
                pl.jclab.refio.core.tools.base.ToolSchema("read_file", "Read a file", mapOf("type" to "object")),
            )
            coEvery { toolExecutor.executeTool(any(), any()) } returns
                ToolResult(success = true, output = "file contents")

            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any(),
                )
            } returnsMany listOf(
                // Iteration 1 — native active, but the model returns the JSON envelope in text (one slip).
                createLLMResponse("""{"response":"reading","actions":[{"tool":"read_file","arguments":{"path":"a.kt"}}]}"""),
                // Iteration 2 — the model uses the native channel properly and finishes. Streak resets.
                LLMResponse(
                    content = "Analysis complete.",
                    usage = LLMUsage(10, 5, 15),
                    model = "gpt-5.5", provider = "openai", cost = 0.0, finishReason = "stop",
                    nativeToolCalls = emptyList(),
                ),
            )

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId, userInput = "Assess risk", mode = TaskMode.AGENT,
                model = "gpt-5.5", provider = "openai",
            )

            assertTrue(result.success, "the native finish should complete the turn: ${result.response}")
            assertFalse(
                NativeToolsFallbackTracker.isFallback("gpt-5.5"),
                "a single envelope slip must NOT persistently demote a capable native model (docs/0068 R1)",
            )
        }

        @Test
        fun `two consecutive envelope slips still demote the model off native`() = runTest {
            // The demotion must not be disabled — it just needs more than one strike. Two consecutive
            // native-ignored envelope responses are a real "this model won't use native here" signal, so
            // the model is marked as a fallback (and native tools dropped for the rest of the turn).
            every { toolRegistry.hasTool("read_file") } returns true
            every { toolRegistry.getToolSchemas(any(), any(), any()) } returns listOf(
                pl.jclab.refio.core.tools.base.ToolSchema("read_file", "Read a file", mapOf("type" to "object")),
            )
            coEvery { toolExecutor.executeTool(any(), any()) } returns
                ToolResult(success = true, output = "file contents")

            val kwargsPerCall = mutableListOf<Map<String, Any>>()
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = capture(kwargsPerCall),
                )
            } returnsMany listOf(
                createLLMResponse("""{"response":"reading a","actions":[{"tool":"read_file","arguments":{"path":"a.kt"}}]}"""),
                createLLMResponse("""{"response":"reading b","actions":[{"tool":"read_file","arguments":{"path":"b.kt"}}]}"""),
                createLLMResponse("""{"actions":[],"response":"Done"}"""),
            )

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId, userInput = "Assess risk", mode = TaskMode.AGENT,
                model = "gpt-5.5", provider = "openai",
            )

            assertTrue(result.success, "the turn should still finish: ${result.response}")
            assertTrue(
                NativeToolsFallbackTracker.isFallback("gpt-5.5"),
                "two consecutive envelope slips must demote the model (docs/0068 R1)",
            )
            // After demotion (end of iteration 2) native tools are no longer offered.
            assertTrue(kwargsPerCall[0].containsKey("native_tools"), "iter 1 must still offer native")
            assertTrue(kwargsPerCall[1].containsKey("native_tools"), "iter 2 must still offer native")
            assertTrue(!kwargsPerCall[2].containsKey("native_tools"), "iter 3 must run on the demoted JSON path")
        }

        @Test
        fun `envelope slips with EMPTY native list (real adapter shape) are executed and demote after two`() = runTest {
            // Codex adversarial-review regression (docs/0068): the existing slip tests model the slip as
            // nativeToolCalls = null, but real adapters return emptyList() when native tools were requested
            // and the model produced 0 native calls. With the empty-list shape the envelope used to be
            // dropped as authoritative "finished" — the tool never ran AND the demotion streak never
            // advanced. Both must now work: each slip's read_file executes, and two consecutive slips demote.
            every { toolRegistry.hasTool("read_file") } returns true
            every { toolRegistry.getToolSchemas(any(), any(), any()) } returns listOf(
                pl.jclab.refio.core.tools.base.ToolSchema("read_file", "Read a file", mapOf("type" to "object")),
            )
            coEvery { toolExecutor.executeTool(any(), any()) } returns
                ToolResult(success = true, output = "file contents")

            fun emptyNativeSlip(content: String) = LLMResponse(
                content = content,
                usage = LLMUsage(100, 50, 150),
                model = "gpt-5.5", provider = "openai", cost = 0.0, finishReason = "stop",
                nativeToolCalls = emptyList(),
            )

            val kwargsPerCall = mutableListOf<Map<String, Any>>()
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = capture(kwargsPerCall),
                )
            } returnsMany listOf(
                emptyNativeSlip("""{"response":"reading a","actions":[{"tool":"read_file","arguments":{"path":"a.kt"}}]}"""),
                emptyNativeSlip("""{"response":"reading b","actions":[{"tool":"read_file","arguments":{"path":"b.kt"}}]}"""),
                createLLMResponse("""{"actions":[],"response":"Done"}"""),
            )

            val result = agentTurnLoop.runTurn(
                taskId = testTaskId, userInput = "Assess risk", mode = TaskMode.AGENT,
                model = "gpt-5.5", provider = "openai",
            )

            assertTrue(result.success, "the turn should finish: ${result.response}")
            // The KEY fix: the envelope was NOT dropped — read_file ran for both slips (old behavior ran 0).
            coVerify(exactly = 2) { toolExecutor.executeTool(match { it.name == "read_file" }, any()) }
            assertTrue(
                NativeToolsFallbackTracker.isFallback("gpt-5.5"),
                "two consecutive empty-native envelope slips must demote the model (docs/0068)",
            )
            assertTrue(kwargsPerCall[0].containsKey("native_tools"), "iter 1 must still offer native")
            assertTrue(kwargsPerCall[1].containsKey("native_tools"), "iter 2 must still offer native")
            assertTrue(!kwargsPerCall[2].containsKey("native_tools"), "iter 3 must run on the demoted JSON path")
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
            // Single 16-arg stub matching the current ChatMessageRepository.create signature:
            // (taskId, role, content, thinking, metadata, toolCalls, toolCallId, subtaskId,
            //  isSummarized, rawOutput, tokensIn, tokensOut, cost, agentInstanceId, agentName, agentDepth)
            every {
                chatMessageRepository.create(
                    any(), any(), any(),
                    any(), any(), any(), any(), any(),
                    any(), any(),
                    any(), any(), any(),
                    any(), any(), any()
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
                    isSummarized = arg(8),
                    rawOutput = arg(9),
                    tokensIn = arg(10),
                    tokensOut = arg(11),
                    cost = arg(12),
                    createdAt = System.currentTimeMillis()
                )
                messages += message
                message
            }
            every {
                chatMessageRepository.createToolResult(
                    taskId = any(),
                    toolCallId = any(),
                    subtaskId = any(),
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
                    content = arg(3),
                    thinking = null,
                    metadata = arg(6),
                    toolCalls = null,
                    toolCallId = secondArg(),
                    isSummarized = arg(4),
                    rawOutput = arg(5),
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
                    // Decision-turn source is now mode-suffixed ("AgentTurnLoop:PLAN"/":AGENT"); match the prefix.
                    source = match { it?.startsWith("AgentTurnLoop") == true },
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
            val listener = mockk<pl.jclab.refio.core.services.turn.TurnEventListener>(relaxed = true)

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
            val listener = mockk<pl.jclab.refio.core.services.turn.TurnEventListener>(relaxed = true)

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

    /**
     * Regression tests for the guardian re-entry answer-preservation fix (option A).
     *
     * Bug (observed 2026-05, sessions 54cf9c8c / 070ab0e5): the model streams a complete answer
     * to the user's bubble, [NextSpeakerJudgeGuardian] returns a MODEL verdict (false positive on
     * a weak judge model), and the loop re-enters BEFORE persisting that answer. When the re-entry
     * adds no new tool call (the judge's no-progress short-circuit to Pass), the degraded follow-up
     * response was finalized instead — replacing the good answer the user saw and losing it from
     * history. The fix keeps the discarded answer and restores it at finalize, but only when the
     * re-entry produced no tool work; if a tool ran, the later (now-evidenced) response wins.
     *
     * Uses a scripted guardian (re-enter once, then Pass) so the behaviour is deterministic and
     * does not depend on the weak judge model's verdicts.
     */
    @Nested
    inner class GuardianReentryAnswerPreservationTests {

        private fun reenterOnceRegistry() =
            GuardianRegistry(listOf(ScriptedReenterOnceGuardian()))

        @Test
        fun `restores the discarded answer when a re-entry adds no tool work`() = runTest {
            // iter1: a complete answer (shown to the user) -> guardian re-enters.
            // iter2: a degraded re-phrasing with no tool call -> guardian short-circuits to Pass.
            // The fix must finalize iter1's answer, not iter2's.
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } returnsMany listOf(
                createLLMResponse("""{"actions":[],"response":"GOOD COMPLETE ANSWER","intent":"analysis"}"""),
                createLLMResponse("""{"actions":[],"response":"worse re-phrasing","intent":"analysis"}""")
            )

            val loop = buildAgentTurnLoop(NoopTaskVerifier(), reenterOnceRegistry())

            val result = loop.runTurn(
                taskId = testTaskId,
                userInput = "Summarize the three mechanisms",
                mode = TaskMode.AGENT
            )

            assertTrue(result.success)
            assertEquals(2, result.iterations, "expected one re-entry")
            // result.response is the exact value finalize persists as the assistant message
            // (both derive from the same effectiveResponse), so asserting it proves the
            // discarded answer — not the degraded re-phrasing — is what survives.
            assertEquals(
                "GOOD COMPLETE ANSWER",
                result.response,
                "the answer the user saw must survive a no-progress re-entry, not be replaced by the degraded one"
            )
        }

        @Test
        fun `keeps the re-entry answer when a tool ran after the re-entry`() = runTest {
            // iter1: partial answer -> guardian re-enters.
            // iter2: the nudge works, the model calls a read-only tool (real progress).
            // iter3: a now-evidenced final answer -> guardian Pass.
            // Because a tool ran AFTER the re-entry, the iter3 answer (not the stale iter1
            // candidate) must be finalized — otherwise completed work would be hidden.
            val readFileTool = mockk<pl.jclab.refio.core.tools.base.Tool>(relaxed = true) {
                every { name } returns "read_file"
                every { mode } returns pl.jclab.refio.core.tools.base.ToolMode.READ_ONLY
            }
            every { toolRegistry.getTool("read_file") } returns readFileTool
            every { toolRegistry.toSubtaskKind(any()) } answers {
                SubtaskKind.valueOf(firstArg<String>().uppercase())
            }
            every { subtaskRepository.getMaxOrderIndex(testTaskId) } returns -1
            every {
                subtaskRepository.create(
                    taskId = any(), orderIndex = any(), kind = any(), description = any(),
                    paramsJson = any(), stepPlanJson = any(), requiresApproval = any(),
                    status = any(), llmModel = any(), llmProvider = any()
                )
            } answers {
                createMockSubtask(
                    id = "subtask-1", orderIndex = secondArg(), kind = thirdArg(),
                    status = arg(7), description = arg(3), paramsJson = arg(4)
                )
            }
            coEvery { toolExecutor.executeTool(any(), any()) } returns
                ToolResult(success = true, output = "file contents: evidence here")
            coEvery { toolResultSummarizer.summarizeToolResult(any(), any(), any()) } answers {
                ToolResultSummary(summary = secondArg(), wasSummarized = false, tokensIn = 0, tokensOut = 0, cost = 0.0)
            }

            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } returnsMany listOf(
                createLLMResponse("""{"actions":[],"response":"partial, still working","intent":"analysis"}"""),
                createLLMResponse("""{"actions":[{"tool":"read_file","arguments":{"path":"x.kt"}}],"response":"reading the file","intent":"implementation"}"""),
                createLLMResponse("""{"actions":[],"response":"COMPLETE WITH EVIDENCE","intent":"analysis"}""")
            )

            val loop = buildAgentTurnLoop(NoopTaskVerifier(), reenterOnceRegistry())

            val result = loop.runTurn(
                taskId = testTaskId,
                userInput = "Find and summarize the file",
                mode = TaskMode.AGENT
            )

            assertTrue(result.success)
            assertEquals(3, result.iterations)
            assertEquals(
                "COMPLETE WITH EVIDENCE",
                result.response,
                "when the re-entry produced real tool work, its later answer must win — not the stale pre-re-entry candidate"
            )
        }
    }

    @Nested
    inner class NativeToolTemplateParseErrorTests {

        // The discriminator that decides whether a 500 gets the one-shot native→JSON retry.
        // It MUST fire only on Ollama's tool-call template parser signature — never on a
        // generic server error — otherwise a genuinely fatal 500 would be masked by a futile
        // JSON-path retry instead of surfacing.

        @Test
        fun `detects Ollama tool-template XML syntax error on the wrapped cause`() {
            val e = RefioError.LLMError(
                provider = "ollama",
                model = "qwen3.5:9b",
                originalCause = IllegalStateException(
                    "Ollama API error (HTTP 500): {\"error\":\"XML syntax error on line 18: " +
                        "element <parameter> closed by </function>\"}"
                )
            )
            assertTrue(agentTurnLoop.isNativeToolTemplateParseError(e))
        }

        @Test
        fun `detects the parse signature nested deeper in the cause chain`() {
            val root = IllegalStateException("element <parameter> closed by </function>")
            val wrapped = RuntimeException("stream processing failed", root)
            assertTrue(agentTurnLoop.isNativeToolTemplateParseError(wrapped))
        }

        @Test
        fun `does not match an unrelated 500 so real fatal errors still surface`() {
            val e = RefioError.LLMError(
                provider = "ollama",
                model = "qwen3.5:9b",
                originalCause = IllegalStateException(
                    "Ollama API error (HTTP 500): {\"error\":\"model runner has unexpectedly stopped\"}"
                )
            )
            assertFalse(agentTurnLoop.isNativeToolTemplateParseError(e))
        }

        @Test
        fun `does not match a transient timeout`() {
            assertFalse(agentTurnLoop.isNativeToolTemplateParseError(RuntimeException("request timed out")))
        }
    }

    @Nested
    inner class DeterministicVerificationTests {

        /** Fake runner: records invocations, replays queued results (last one repeats). */
        inner class RecordingRunner(
            vararg executions: pl.jclab.refio.core.services.turn.VerificationExecution
        ) : pl.jclab.refio.core.services.turn.VerificationCommandRunner {
            val invocations = mutableListOf<String>()
            private val queue = executions.toMutableList()

            override fun run(
                command: String,
                workingDir: java.io.File,
                timeoutSeconds: Int
            ): pl.jclab.refio.core.services.turn.VerificationExecution {
                invocations.add(command)
                return if (queue.size > 1) queue.removeAt(0) else queue.first()
            }
        }

        private fun verifierWith(runner: RecordingRunner): pl.jclab.refio.core.services.turn.TurnVerifier {
            // Explicit verify.command so no marker files are needed; defaults from setup() give
            // verify.enabled=true and verify.max_repair_rounds=2.
            every { configService.getTyped(pl.jclab.refio.core.config.ConfigKeys.VERIFY_COMMAND, any()) } returns "fake-verify"
            return pl.jclab.refio.core.services.turn.TurnVerifier(
                configService = configService,
                projectRoot = java.nio.file.Paths.get("/test/project"),
                runner = runner
            )
        }

        private fun stubWriteTool() {
            val editTool = mockk<pl.jclab.refio.core.tools.base.Tool>(relaxed = true) {
                every { name } returns "advance_code_editing"
                every { mode } returns pl.jclab.refio.core.tools.base.ToolMode.WRITE
            }
            every { toolRegistry.getTool("advance_code_editing") } returns editTool
            coEvery { toolExecutor.executeTool(any(), any()) } returns
                ToolResult(success = true, output = "edited Config.kt")
        }

        private fun stubLlmResponses(vararg responses: LLMResponse) {
            coEvery {
                llmClient.complete(
                    provider = any(), model = any(), messages = any(), systemPrompt = any(),
                    maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                    noEgressEnabled = any(), stream = any(), onChunk = any(), taskId = any(),
                    subtaskId = any(), source = any(), kwargs = any()
                )
            } returnsMany responses.toList()
        }

        private val editCall = """{"response":"editing","actions":[{"tool":"advance_code_editing","arguments":{"path":"Config.kt","instructions":"set MAX_RETRIES to 5"}}]}"""

        @Test
        fun `a turn without file writes is never verified`() = runTest {
            // Guard against the known regression: post-deliverable self-verification must never
            // fire for a purely conversational turn - the build command must not even start.
            val runner = RecordingRunner(
                pl.jclab.refio.core.services.turn.VerificationExecution(exitCode = 1, output = "e: broken")
            )
            val loop = buildAgentTurnLoop(NoopTaskVerifier(), turnVerifier = verifierWith(runner))
            stubLlmResponses(createLLMResponse("""{"actions":[],"response":"Here is the explanation you asked for."}"""))

            val result = loop.runTurn(taskId = testTaskId, userInput = "Explain the config", mode = TaskMode.AGENT)

            assertTrue(result.success)
            assertTrue(runner.invocations.isEmpty(), "no file writes -> verification must not run")
            assertNull(result.verification)
            assertFalse(pl.jclab.refio.core.debug.TurnVerificationTracker.summaryFor(testTaskId).ran)
        }

        @Test
        fun `passing verification finalizes success with a recorded attempt`() = runTest {
            stubWriteTool()
            val runner = RecordingRunner(
                // 1st run: the pre-write baseline (green project). 2nd: the finalization verify.
                pl.jclab.refio.core.services.turn.VerificationExecution(exitCode = 0, output = "BUILD SUCCESSFUL"),
                pl.jclab.refio.core.services.turn.VerificationExecution(exitCode = 0, output = "BUILD SUCCESSFUL")
            )
            val loop = buildAgentTurnLoop(NoopTaskVerifier(), turnVerifier = verifierWith(runner))
            stubLlmResponses(
                createLLMResponse(editCall),
                createLLMResponse("""{"actions":[],"response":"Change applied."}""")
            )

            val result = loop.runTurn(taskId = testTaskId, userInput = "Set MAX_RETRIES to 5", mode = TaskMode.AGENT)

            assertTrue(result.success, "verified deliverable must finalize SUCCESS: ${result.response}")
            assertEquals(2, runner.invocations.size, "pre-write baseline + finalization verify = two runs")
            assertNotNull(result.verification)
            assertTrue(result.verification!!.ran)
            assertEquals(1, result.verification!!.attempts, "the baseline run must not count as a verification attempt")
            assertEquals("PASSED", result.verification!!.result)
        }

        @Test
        fun `failed verification triggers a repair round that can then pass`() = runTest {
            stubWriteTool()
            val runner = RecordingRunner(
                // 1st run: the pre-write baseline (green project), so the finalization failure is
                // attributed to the agent's change and a repair round is triggered.
                pl.jclab.refio.core.services.turn.VerificationExecution(exitCode = 0, output = "BUILD SUCCESSFUL"),
                pl.jclab.refio.core.services.turn.VerificationExecution(
                    exitCode = 1,
                    output = "e: Config.kt:12:5 Unresolved reference: MAX_RETRY\nBUILD FAILED"
                ),
                pl.jclab.refio.core.services.turn.VerificationExecution(exitCode = 0, output = "BUILD SUCCESSFUL")
            )
            val loop = buildAgentTurnLoop(NoopTaskVerifier(), turnVerifier = verifierWith(runner))
            stubLlmResponses(
                createLLMResponse(editCall),
                createLLMResponse("""{"actions":[],"response":"Change applied."}"""),
                createLLMResponse(editCall),
                createLLMResponse("""{"actions":[],"response":"Fixed the typo, change applied."}""")
            )

            val result = loop.runTurn(taskId = testTaskId, userInput = "Set MAX_RETRIES to 5", mode = TaskMode.AGENT)

            assertTrue(result.success, "repaired + re-verified turn must finalize SUCCESS: ${result.response}")
            assertEquals(3, runner.invocations.size, "baseline + failing verify + re-verify after repair = three runs")
            assertEquals(2, result.verification!!.attempts)
            assertEquals("PASSED", result.verification!!.result)
            // The repair message must carry only the extracted error lines, not the full build log.
            verify {
                chatMessageRepository.create(
                    testTaskId, MessageRole.SYSTEM,
                    match {
                        it.startsWith("Verification failed (exit 1)") &&
                            it.contains("Unresolved reference: MAX_RETRY") &&
                            it.contains("Fix them.")
                    },
                    any(), any(), any(), any(), any(), any()
                )
            }
        }

        @Test
        fun `exhausted repair rounds end the turn as verification failure, never faked success`() = runTest {
            stubWriteTool()
            val runner = RecordingRunner(
                // 1st run: the pre-write baseline (green project), so the finalization failures are
                // attributed to the agent. The repeated failure below then drives the repair loop.
                pl.jclab.refio.core.services.turn.VerificationExecution(exitCode = 0, output = "BUILD SUCCESSFUL"),
                pl.jclab.refio.core.services.turn.VerificationExecution(
                    exitCode = 1,
                    output = "irrelevant build chatter\ne: Config.kt:12:5 Unresolved reference: MAX_RETRY"
                )
            )
            val loop = buildAgentTurnLoop(NoopTaskVerifier(), turnVerifier = verifierWith(runner))
            stubLlmResponses(
                createLLMResponse(editCall),
                createLLMResponse("""{"actions":[],"response":"Change applied."}"""),
                createLLMResponse("""{"actions":[],"response":"Tried a fix, change applied."}"""),
                createLLMResponse("""{"actions":[],"response":"Tried another fix, change applied."}""")
            )

            val result = loop.runTurn(taskId = testTaskId, userInput = "Set MAX_RETRIES to 5", mode = TaskMode.AGENT)

            assertFalse(result.success, "an unverifiable deliverable must never be reported as success")
            // baseline + (initial attempt + 2 repair rounds = 3 executions), then stop.
            assertEquals(4, runner.invocations.size, "baseline + round-capped repair loop = four runs")
            assertEquals(3, result.verification!!.attempts)
            assertEquals("FAILED", result.verification!!.result)
            assertTrue(result.response.contains("Verification failed"))
            assertTrue(result.response.contains("Unresolved reference: MAX_RETRY"))
            assertFalse(result.response.contains("irrelevant build chatter"), "full build output must never surface")
            assertEquals(
                pl.jclab.refio.core.debug.TurnFailureMarkerTracker.VERIFICATION_FAILED,
                pl.jclab.refio.core.debug.TurnFailureMarkerTracker.markerFor(testTaskId)
            )
        }
    }
}

/**
 * Test guardian that re-enters exactly once (first terminal point) then passes — mirrors
 * [NextSpeakerJudgeGuardian]'s single-bounded-re-entry shape, including the no-progress
 * short-circuit to Pass on later checks, without depending on a weak judge model.
 */
private class ScriptedReenterOnceGuardian : TurnCompletionGuardian {
    override val name: String = "scripted_reenter_once"
    override suspend fun check(context: GuardianContext): GuardianDecision =
        if (context.priorReentries == 0) {
            GuardianDecision.Reenter(nudge = "STOP — finish the task with a concrete tool call.", reason = "scripted: not done")
        } else {
            GuardianDecision.Pass
        }
}
