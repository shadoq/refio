package pl.jclab.refio.core.services.orchestration

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.services.StepExecutionResult
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReflectionEngineTest {

    private lateinit var llmClient: LLMClient
    private lateinit var promptsService: PromptsService
    private lateinit var configService: ConfigService
    private lateinit var taskRepository: TaskRepository
    private lateinit var subtaskRepository: SubtaskRepository
    private lateinit var toolDescriptionBuilder: ToolDescriptionBuilder

    private lateinit var engine: ReflectionEngine

    @BeforeEach
    fun setup() {
        llmClient = mockk()
        promptsService = mockk()
        configService = mockk()
        taskRepository = mockk()
        subtaskRepository = mockk()
        toolDescriptionBuilder = mockk()

        engine = ReflectionEngine(
            llmClient = llmClient,
            promptsService = promptsService,
            configService = configService,
            taskRepository = taskRepository,
            subtaskRepository = subtaskRepository,
            toolDescriptionBuilder = toolDescriptionBuilder
        )
    }

    private fun createMockTask(
        id: String = "task-123",
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
        projectId = "test-project",
        projectPath = "/test/project",
        rate = null,
        tokensIn = 0,
        tokensOut = 0,
        costUsd = 0.0,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    private fun createMockSubtask(
        id: String = "subtask-${System.nanoTime()}",
        taskId: String = "task-123",
        orderIndex: Int = 1,
        status: TaskStatus = TaskStatus.PENDING
    ) = Subtask(
        id = id,
        taskId = taskId,
        orderIndex = orderIndex,
        kind = SubtaskKind.READ_FILE,
        status = status,
        description = "Test subtask $orderIndex",
        paramsJson = null,
        stepPlanJson = null,
        summary = null,
        requiresApproval = false,
        approvalStatus = ApprovalStatus.PENDING_APPROVAL,
        approvedAt = null,
        result = null,
        errorMessage = null,
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

    private fun setupCommonMocks() {
        every { configService.getModel(any(), any()) } returns Pair("gpt-4", "openai")
        every { configService.getMaxOutputTokens(any()) } returns 4096
        every { configService.get(any()) } returns null
        every { promptsService.getSystemPrompt(any(), any()) } returns "System prompt"
        every { toolDescriptionBuilder.getToolDescriptions(any(), any()) } returns "Tool descriptions"
        every { taskRepository.incrementMetrics(any(), any(), any(), any()) } returns createMockTask()
    }

    @Nested
    inner class ReflectTests {

        @Test
        fun `should return CONTINUE decision for successful step`() = runBlocking {
            // Given
            val task = createMockTask()
            val subtask = createMockSubtask(taskId = task.id, orderIndex = 1, status = TaskStatus.SUCCESS)
            val result = StepExecutionResult(
                status = "success",
                result = null,
                summary = "File read successfully",
                durationMs = 100,
                error = null
            )

            val llmResponse = """{
                "decision": "CONTINUE",
                "reasoning": "Step completed successfully, continue with plan",
                "analysis": "File was read",
                "actions": []
            }"""

            setupCommonMocks()
            every { subtaskRepository.findByStatus(task.id, TaskStatus.PENDING) } returns emptyList()
            every { subtaskRepository.findByTaskId(task.id) } returns listOf(subtask)

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
                content = llmResponse,
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
                model = "gpt-4",
                provider = "openai",
                cost = 0.001
            )

            // When
            val decision = engine.reflect(task, subtask, result)

            // Then
            assertEquals(DecisionType.CONTINUE, decision.decision)
            assertTrue(decision.reasoning.isNotEmpty())
            assertTrue(decision.actions.isEmpty())
        }

        @Test
        fun `should return MODIFY_PLAN decision with actions`() = runBlocking {
            // Given
            val task = createMockTask()
            val subtask = createMockSubtask(taskId = task.id, orderIndex = 1, status = TaskStatus.SUCCESS)
            val result = StepExecutionResult(
                status = "success",
                result = null,
                summary = "File not found, need different approach",
                durationMs = 100,
                error = null
            )

            val llmResponse = """{
                "decision": "MODIFY_PLAN",
                "reasoning": "Need to add step to search for file first",
                "analysis": "Target file does not exist",
                "actions": [
                    {
                        "type": "ADD_STEP",
                        "after_step": 1,
                        "description": "Search for similar files",
                        "kind": "file_search",
                        "suggested_params": {"pattern": "*.kt"}
                    }
                ]
            }"""

            setupCommonMocks()
            every { subtaskRepository.findByStatus(task.id, TaskStatus.PENDING) } returns listOf(
                createMockSubtask(taskId = task.id, orderIndex = 2)
            )
            every { subtaskRepository.findByTaskId(task.id) } returns listOf(subtask)

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
                content = llmResponse,
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
                model = "gpt-4",
                provider = "openai",
                cost = 0.001
            )

            // When
            val decision = engine.reflect(task, subtask, result)

            // Then
            assertEquals(DecisionType.MODIFY_PLAN, decision.decision)
            assertEquals(1, decision.actions.size)
            assertTrue(decision.actions.first() is ReflectionAction.AddStep)

            val addStepAction = decision.actions.first() as ReflectionAction.AddStep
            assertEquals(1, addStepAction.afterStep)
            assertEquals("Search for similar files", addStepAction.description)
            assertEquals("file_search", addStepAction.kind)
        }

        @Test
        fun `should return ASK_USER decision with question`() = runBlocking {
            // Given
            val task = createMockTask()
            val subtask = createMockSubtask(taskId = task.id, orderIndex = 1, status = TaskStatus.SUCCESS)
            val result = StepExecutionResult(
                status = "success",
                result = null,
                summary = "Found multiple options",
                durationMs = 100,
                error = null
            )

            val llmResponse = """{
                "decision": "ASK_USER",
                "reasoning": "Multiple options found, need user guidance",
                "analysis": "Found 3 matching files",
                "question": "Which file should I modify?",
                "question_options": ["file1.kt", "file2.kt", "file3.kt"],
                "actions": []
            }"""

            setupCommonMocks()
            every { subtaskRepository.findByStatus(task.id, TaskStatus.PENDING) } returns emptyList()
            every { subtaskRepository.findByTaskId(task.id) } returns listOf(subtask)

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
                content = llmResponse,
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
                model = "gpt-4",
                provider = "openai",
                cost = 0.001
            )

            // When
            val decision = engine.reflect(task, subtask, result)

            // Then
            assertEquals(DecisionType.ASK_USER, decision.decision)
            assertNotNull(decision.question)
            assertEquals("Which file should I modify?", decision.question)
            assertNotNull(decision.questionOptions)
            assertEquals(3, decision.questionOptions!!.size)
        }

        @Test
        fun `should return ABORT decision for unrecoverable error`() = runBlocking {
            // Given
            val task = createMockTask()
            val subtask = createMockSubtask(taskId = task.id, orderIndex = 1, status = TaskStatus.FAILED)
            val result = StepExecutionResult(
                status = "failed",
                result = null,
                summary = "Critical error",
                durationMs = 100,
                error = "Permission denied: cannot access protected directory"
            )

            val llmResponse = """{
                "decision": "ABORT",
                "reasoning": "Cannot continue due to permission issues",
                "analysis": "Protected directory access is blocked",
                "actions": []
            }"""

            setupCommonMocks()
            every { subtaskRepository.findByStatus(task.id, TaskStatus.PENDING) } returns emptyList()
            every { subtaskRepository.findByTaskId(task.id) } returns listOf(subtask)

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
                content = llmResponse,
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
                model = "gpt-4",
                provider = "openai",
                cost = 0.001
            )

            // When
            val decision = engine.reflect(task, subtask, result)

            // Then
            assertEquals(DecisionType.ABORT, decision.decision)
            assertTrue(decision.reasoning.contains("permission"))
        }

        @Test
        fun `should fallback to CONTINUE on LLM failure`() = runBlocking {
            // Given
            val task = createMockTask()
            val subtask = createMockSubtask(taskId = task.id, orderIndex = 1, status = TaskStatus.SUCCESS)
            val result = StepExecutionResult(
                status = "success",
                result = null,
                summary = "Step completed",
                durationMs = 100,
                error = null
            )

            setupCommonMocks()
            every { subtaskRepository.findByStatus(task.id, TaskStatus.PENDING) } returns emptyList()
            every { subtaskRepository.findByTaskId(task.id) } returns listOf(subtask)

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

            // When
            val decision = engine.reflect(task, subtask, result)

            // Then
            assertEquals(DecisionType.CONTINUE, decision.decision)
            assertTrue(decision.reasoning.contains("Reflection failed"))
        }
    }

    @Nested
    inner class ActionParsingTests {

        @Test
        fun `should parse SKIP_STEP action`() = runBlocking {
            // Given
            val task = createMockTask()
            val subtask = createMockSubtask(taskId = task.id, orderIndex = 1)
            val result = StepExecutionResult(
                status = "success",
                result = null,
                summary = "Done",
                durationMs = 100,
                error = null
            )

            val llmResponse = """{
                "decision": "MODIFY_PLAN",
                "reasoning": "Skip redundant step",
                "analysis": "Step already done",
                "actions": [
                    {
                        "type": "SKIP_STEP",
                        "step": 2,
                        "reason": "Already completed by previous step"
                    }
                ]
            }"""

            setupCommonMocks()
            every { subtaskRepository.findByStatus(task.id, TaskStatus.PENDING) } returns emptyList()
            every { subtaskRepository.findByTaskId(task.id) } returns listOf(subtask)

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
                content = llmResponse,
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
                model = "gpt-4",
                provider = "openai",
                cost = 0.001
            )

            // When
            val decision = engine.reflect(task, subtask, result)

            // Then
            assertEquals(1, decision.actions.size)
            assertTrue(decision.actions.first() is ReflectionAction.SkipStep)

            val action = decision.actions.first() as ReflectionAction.SkipStep
            assertEquals(2, action.step)
            assertEquals("Already completed by previous step", action.reason)
        }

        @Test
        fun `should parse MODIFY_STEP action`() = runBlocking {
            // Given
            val task = createMockTask()
            val subtask = createMockSubtask(taskId = task.id, orderIndex = 1)
            val result = StepExecutionResult(
                status = "success",
                result = null,
                summary = "Done",
                durationMs = 100,
                error = null
            )

            val llmResponse = """{
                "decision": "MODIFY_PLAN",
                "reasoning": "Update step with new info",
                "analysis": "Found better approach",
                "actions": [
                    {
                        "type": "MODIFY_STEP",
                        "step": 3,
                        "new_description": "Updated step description",
                        "new_params": {"path": "/new/path"}
                    }
                ]
            }"""

            setupCommonMocks()
            every { subtaskRepository.findByStatus(task.id, TaskStatus.PENDING) } returns emptyList()
            every { subtaskRepository.findByTaskId(task.id) } returns listOf(subtask)

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
                content = llmResponse,
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
                model = "gpt-4",
                provider = "openai",
                cost = 0.001
            )

            // When
            val decision = engine.reflect(task, subtask, result)

            // Then
            assertEquals(1, decision.actions.size)
            assertTrue(decision.actions.first() is ReflectionAction.ModifyStep)

            val action = decision.actions.first() as ReflectionAction.ModifyStep
            assertEquals(3, action.step)
            assertEquals("Updated step description", action.newDescription)
            assertNotNull(action.newParams)
        }

        @Test
        fun `should parse RETRY_STEP action`() = runBlocking {
            // Given
            val task = createMockTask()
            val subtask = createMockSubtask(taskId = task.id, orderIndex = 1)
            val result = StepExecutionResult(
                status = "failed",
                result = null,
                summary = "Transient failure",
                durationMs = 100,
                error = "Timeout"
            )

            val llmResponse = """{
                "decision": "MODIFY_PLAN",
                "reasoning": "Retry after transient failure",
                "analysis": "Network timeout, should retry",
                "actions": [
                    {
                        "type": "RETRY_STEP",
                        "step": 1,
                        "reason": "Network timeout, worth retrying"
                    }
                ]
            }"""

            setupCommonMocks()
            every { subtaskRepository.findByStatus(task.id, TaskStatus.PENDING) } returns emptyList()
            every { subtaskRepository.findByTaskId(task.id) } returns listOf(subtask)

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
                content = llmResponse,
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
                model = "gpt-4",
                provider = "openai",
                cost = 0.001
            )

            // When
            val decision = engine.reflect(task, subtask, result)

            // Then
            assertEquals(1, decision.actions.size)
            assertTrue(decision.actions.first() is ReflectionAction.RetryStep)

            val action = decision.actions.first() as ReflectionAction.RetryStep
            assertEquals(1, action.step)
            assertTrue(action.reason.contains("timeout"))
        }

        @Test
        fun `should parse multiple actions`() = runBlocking {
            // Given
            val task = createMockTask()
            val subtask = createMockSubtask(taskId = task.id, orderIndex = 1)
            val result = StepExecutionResult(
                status = "success",
                result = null,
                summary = "Done",
                durationMs = 100,
                error = null
            )

            val llmResponse = """{
                "decision": "MODIFY_PLAN",
                "reasoning": "Multiple changes needed",
                "analysis": "Complex modification required",
                "actions": [
                    {"type": "SKIP_STEP", "step": 2, "reason": "Not needed"},
                    {"type": "ADD_STEP", "after_step": 3, "description": "New step", "kind": "read_file", "suggested_params": {}},
                    {"type": "MODIFY_STEP", "step": 4, "new_description": "Updated"}
                ]
            }"""

            setupCommonMocks()
            every { subtaskRepository.findByStatus(task.id, TaskStatus.PENDING) } returns emptyList()
            every { subtaskRepository.findByTaskId(task.id) } returns listOf(subtask)

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
                content = llmResponse,
                usage = LLMUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
                model = "gpt-4",
                provider = "openai",
                cost = 0.001
            )

            // When
            val decision = engine.reflect(task, subtask, result)

            // Then
            assertEquals(3, decision.actions.size)
            assertTrue(decision.actions[0] is ReflectionAction.SkipStep)
            assertTrue(decision.actions[1] is ReflectionAction.AddStep)
            assertTrue(decision.actions[2] is ReflectionAction.ModifyStep)
        }
    }

    @Nested
    inner class MetricsTrackingTests {

        @Test
        fun `should track LLM usage metrics`() = runBlocking {
            // Given
            val task = createMockTask()
            val subtask = createMockSubtask(taskId = task.id, orderIndex = 1)
            val result = StepExecutionResult(
                status = "success",
                result = null,
                summary = "Done",
                durationMs = 100,
                error = null
            )

            val llmResponse = """{
                "decision": "CONTINUE",
                "reasoning": "All good",
                "analysis": "",
                "actions": []
            }"""

            setupCommonMocks()
            every { subtaskRepository.findByStatus(task.id, TaskStatus.PENDING) } returns emptyList()
            every { subtaskRepository.findByTaskId(task.id) } returns listOf(subtask)

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
                content = llmResponse,
                usage = LLMUsage(inputTokens = 250, outputTokens = 75, totalTokens = 325),
                model = "gpt-4",
                provider = "openai",
                cost = 0.005
            )

            // When
            engine.reflect(task, subtask, result)

            // Then
            verify {
                taskRepository.incrementMetrics(
                    id = task.id,
                    tokensIn = 250,
                    tokensOut = 75,
                    costUsd = 0.005
                )
            }
        }
    }
}
