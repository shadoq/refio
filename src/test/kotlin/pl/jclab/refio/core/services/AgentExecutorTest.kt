package pl.jclab.refio.core.services

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentExecutorTest {

    private lateinit var taskRepository: TaskRepository
    private lateinit var subtaskRepository: SubtaskRepository
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var llmClient: LLMClient
    private lateinit var promptsService: PromptsService
    private lateinit var configService: ConfigService
    private lateinit var stepPlanner: StepPlanner

    private lateinit var executor: AgentExecutor

    @BeforeEach
    fun setup() {
        taskRepository = mockk()
        subtaskRepository = mockk()
        toolExecutor = mockk()
        llmClient = mockk()
        promptsService = mockk()
        configService = mockk()
        stepPlanner = mockk()

        every { promptsService.getSystemPrompt(any(), any()) } returns "System prompt"
        every { configService.getModel(any(), any()) } returns Pair("gpt-4", "openai")
        every { taskRepository.incrementMetrics(any(), any(), any(), any()) } returns createMockTask()
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
        } returns pl.jclab.refio.core.llm.LLMResponse(
            content = "Summary",
            usage = pl.jclab.refio.core.llm.LLMUsage(inputTokens = 1, outputTokens = 1, totalTokens = 2),
            model = "gpt-4",
            provider = "openai",
            cost = 0.0
        )

        executor = AgentExecutor(
            taskRepository = taskRepository,
            subtaskRepository = subtaskRepository,
            toolExecutor = toolExecutor,
            llmClient = llmClient,
            promptsService = promptsService,
            configService = configService,
            stepPlanner = stepPlanner
        )

        every { subtaskRepository.countByTaskId(any()) } returns 1L
    }

    private fun createMockTask(
        id: String = "task-123",
        mode: TaskMode = TaskMode.AGENT
    ) = Task(
        id = id,
        name = "Test Task",
        mode = mode,
        status = TaskStatus.RUNNING,
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
        id: String = "subtask-123",
        taskId: String = "task-123",
        orderIndex: Int = 1,
        status: TaskStatus = TaskStatus.PENDING,
        stepPlanJson: String? = null
    ) = Subtask(
        id = id,
        taskId = taskId,
        orderIndex = orderIndex,
        kind = SubtaskKind.READ_FILE,
        status = status,
        description = "Test subtask",
        paramsJson = null,
        stepPlanJson = stepPlanJson,
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

    @Nested
    inner class PlanStepTests {

        @Test
        fun `should throw when StepPlanner not available`() = runBlocking {
            // Given
            val executorWithoutPlanner = AgentExecutor(
                taskRepository = taskRepository,
                subtaskRepository = subtaskRepository,
                toolExecutor = toolExecutor,
                llmClient = llmClient,
                promptsService = promptsService,
                configService = configService,
                stepPlanner = null
            )

            // When/Then
            assertThrows<IllegalStateException> {
                executorWithoutPlanner.planStep("task-123", "subtask-123")
            }
        }

        @Test
        fun `should return error when subtask not found`() = runBlocking {
            // Given
            every { subtaskRepository.findById(any()) } returns null

            // When
            val result = executor.planStep("task-123", "non-existent")

            // Then - returns error in result, not exception
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not found"))
        }

        @Test
        fun `should return error when subtask belongs to different task`() = runBlocking {
            // Given
            val subtask = createMockSubtask(id = "subtask-123", taskId = "other-task")
            every { subtaskRepository.findById("subtask-123") } returns subtask

            // When
            val result = executor.planStep("task-123", "subtask-123")

            // Then
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("does not belong"))
        }

        @Test
        fun `should return error when subtask not in PENDING state`() = runBlocking {
            // Given
            val subtask = createMockSubtask(status = TaskStatus.SUCCESS)
            every { subtaskRepository.findById("subtask-123") } returns subtask

            // When
            val result = executor.planStep("task-123", "subtask-123")

            // Then
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("PENDING"))
        }
    }

    @Nested
    inner class ExecuteStepTests {

        @Test
        fun `should return error when subtask not found`() = runBlocking {
            // Given
            every { subtaskRepository.findById(any()) } returns null
            every { subtaskRepository.updateStatus(any(), any()) } returns createMockSubtask(id = "non-existent")
            every { subtaskRepository.updateResult(any(), any(), any()) } returns createMockSubtask(id = "non-existent")

            // When
            val result = executor.executeStep("task-123", "non-existent")

            // Then
            assertEquals("failed", result.status)
            assertTrue(result.error!!.contains("not found"))
        }

        @Test
        fun `should return error when subtask belongs to different task`() = runBlocking {
            // Given
            val subtask = createMockSubtask(id = "subtask-123", taskId = "other-task")
            every { subtaskRepository.findById("subtask-123") } returns subtask
            every { subtaskRepository.updateStatus(any(), any()) } returns subtask
            every { subtaskRepository.updateResult(any(), any(), any()) } returns subtask

            // When
            val result = executor.executeStep("task-123", "subtask-123")

            // Then
            assertEquals("failed", result.status)
            assertTrue(result.error!!.contains("does not belong"))
        }

        @Test
        fun `should return error when subtask not in valid state`() = runBlocking {
            // Given
            val subtask = createMockSubtask(status = TaskStatus.SUCCESS)
            every { subtaskRepository.findById("subtask-123") } returns subtask

            // When
            val result = executor.executeStep("task-123", "subtask-123")

            // Then
            assertEquals("failed", result.status)
            assertNotNull(result.error)
        }

        @Test
        fun `should handle execution with empty step plan`() = runBlocking {
            // Given
            val taskId = "task-123"
            val subtaskId = "subtask-123"
            val subtask = createMockSubtask(
                id = subtaskId,
                taskId = taskId,
                status = TaskStatus.PLANNED,
                stepPlanJson = null
            )
            val updatedSubtask = subtask.copy(status = TaskStatus.SUCCESS)

            val executionResult = ToolExecutionResult(
                toolsExecuted = 0,
                outputs = emptyList(),
                success = true,
                errors = emptyList()
            )

            every { subtaskRepository.findById(subtaskId) } returnsMany listOf(subtask, updatedSubtask)
            every { subtaskRepository.updateStatus(subtaskId, any()) } returns updatedSubtask
            every { subtaskRepository.updateResult(subtaskId, any(), any()) } returns updatedSubtask
            every { subtaskRepository.updateSummary(subtaskId, any()) } returns updatedSubtask
            coEvery { toolExecutor.executeToolsWithStreaming(any(), any(), any()) } returns executionResult

            mockkConstructor(StepSummarizer::class)
            coEvery { anyConstructed<StepSummarizer>().generateSummary(any(), any(), any()) } returns "No tools executed"

            // When
            val result = executor.executeStep(taskId, subtaskId)

            // Then
            assertEquals("success", result.status)
        }

        @Test
        fun `should update status to RUNNING before execution`() = runBlocking {
            // Given
            val taskId = "task-123"
            val subtaskId = "subtask-123"
            val stepPlanJson = """{"tools": [{"name": "read_file", "params": {"path": "/test"}}]}"""
            val subtask = createMockSubtask(
                id = subtaskId,
                taskId = taskId,
                status = TaskStatus.PLANNED,
                stepPlanJson = stepPlanJson
            )
            val updatedSubtask = subtask.copy(status = TaskStatus.SUCCESS)

            val executionResult = ToolExecutionResult(
                toolsExecuted = 1,
                outputs = emptyList(),
                success = true,
                errors = emptyList()
            )

            every { subtaskRepository.findById(subtaskId) } returnsMany listOf(subtask, updatedSubtask)
            every { subtaskRepository.updateStatus(subtaskId, any()) } returns updatedSubtask
            every { subtaskRepository.updateResult(subtaskId, any(), any()) } returns updatedSubtask
            every { subtaskRepository.updateSummary(subtaskId, any()) } returns updatedSubtask
            coEvery { toolExecutor.executeToolsWithStreaming(any(), any(), any()) } returns executionResult

            mockkConstructor(StepSummarizer::class)
            coEvery { anyConstructed<StepSummarizer>().generateSummary(any(), any(), any()) } returns "Done"

            // When
            executor.executeStep(taskId, subtaskId)

            // Then
            verify { subtaskRepository.updateStatus(subtaskId, TaskStatus.RUNNING) }
        }
    }
}
