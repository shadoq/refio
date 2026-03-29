package pl.jclab.refio.core.api.routers

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import pl.jclab.refio.core.api.ToolCallSpec
import pl.jclab.refio.core.db.ApprovalStatus
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.services.AgentExecutor
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ContextService
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.services.StepExecutionResult
import pl.jclab.refio.core.services.StepPlanResult
import pl.jclab.refio.core.services.ExecutionPlan
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.llm.LLMClient
import java.nio.file.Paths

class AgentRouterTest {

    private lateinit var agentExecutor: AgentExecutor
    private lateinit var taskRepository: TaskRepository
    private lateinit var subtaskRepository: SubtaskRepository
    private lateinit var chatMessageRepository: ChatMessageRepository
    private lateinit var configService: ConfigService
    private lateinit var llmClient: LLMClient
    private lateinit var promptsService: PromptsService
    private lateinit var contextService: ContextService
    private var ideProject: Any? = null
    private lateinit var toolDescriptionBuilder: ToolDescriptionBuilder
    private lateinit var agentRouter: AgentRouter

    @BeforeEach
    fun setup() {
        agentExecutor = mockk()
        taskRepository = mockk()
        subtaskRepository = mockk()
        chatMessageRepository = mockk()
        configService = mockk()
        llmClient = mockk()
        promptsService = mockk()
        contextService = mockk()
        ideProject = null // Platform-independent test
        toolDescriptionBuilder = mockk()
        agentRouter = AgentRouter(
            agentExecutor = agentExecutor,
            taskRepository = taskRepository,
            subtaskRepository = subtaskRepository,
            chatMessageRepository = chatMessageRepository,
            configService = configService,
            llmClient = llmClient,
            promptsService = promptsService,
            contextService = contextService,
            projectRoot = Paths.get("D:/test"),
            ideProject = ideProject,
            toolDescriptionBuilder = toolDescriptionBuilder
        )
    }

    @Test
    fun `planSubtaskStep generates step plan and saves approval message`() = runBlocking {
        // Given
        val taskId = "task-123"
        val subtaskId = "subtask-456"
        val plan = ExecutionPlan(
            tools = listOf(
                ToolCallSpec(
                    name = "read_file",
                    params = mapOf("path" to "/path/to/file"),
                    expectedOutput = "File contents"
                )
            ),
            description = "Read configuration file",
            estimatedDurationMs = 1000,
            dependencies = emptyList()
        )
        val subtask = buildSubtask(
            id = subtaskId,
            taskId = taskId,
            orderIndex = 1,
            requiresApproval = true
        )
        coEvery { agentExecutor.planStep(taskId, subtaskId) } returns StepPlanResult(
            plan = plan,
            durationMs = 120,
            llmMetrics = null,
            error = null
        )
        every { subtaskRepository.findById(subtaskId) } returns subtask
        every {
            chatMessageRepository.create(
                taskId = taskId,
                role = MessageRole.ASSISTANT,
                content = any(),
                metadata = any(),
                tokensIn = any(),
                tokensOut = any(),
                cost = any()
            )
        } returns mockk()

        // When
        val response = agentRouter.planSubtaskStep(taskId, subtaskId)

        // Then
        assertEquals(1, response.tools.size)
        assertEquals("read_file", response.tools[0].name)
        assertEquals("Read configuration file", response.description)
        verify(exactly = 1) {
            chatMessageRepository.create(
                taskId = taskId,
                role = MessageRole.ASSISTANT,
                content = match { it.contains("Approve execution?") },
                metadata = any(),
                tokensIn = any(),
                tokensOut = any(),
                cost = any()
            )
        }
    }

    @Test
    fun `executeSubtaskStep executes planned step`() = runBlocking {
        // Given
        val taskId = "task-123"
        val subtaskId = "subtask-456"
        val subtask = buildSubtask(
            id = subtaskId,
            taskId = taskId,
            orderIndex = 2,
            requiresApproval = false
        )
        coEvery { agentExecutor.executeStep(taskId, subtaskId) } returns StepExecutionResult(
            status = "success",
            result = null,
            summary = "Successfully read file",
            durationMs = 1234
        )
        every { subtaskRepository.findById(subtaskId) } returns subtask
        every {
            chatMessageRepository.create(
                taskId = taskId,
                role = MessageRole.ASSISTANT,
                content = any(),
                metadata = any(),
                tokensIn = any(),
                tokensOut = any(),
                cost = any()
            )
        } returns mockk()

        // When
        val response = agentRouter.executeSubtaskStep(taskId, subtaskId)

        // Then
        assertEquals("success", response.status)
        assertEquals("Successfully read file", response.summary)
        assertNull(response.error)
        verify(exactly = 1) {
            chatMessageRepository.create(
                taskId = taskId,
                role = MessageRole.ASSISTANT,
                content = match { it.contains("Step 2") },
                metadata = any(),
                tokensIn = any(),
                tokensOut = any(),
                cost = any()
            )
        }
    }

    @Test
    fun `executeSubtaskStep handles errors`() = runBlocking {
        // Given
        val taskId = "task-123"
        val subtaskId = "subtask-456"
        val subtask = buildSubtask(
            id = subtaskId,
            taskId = taskId,
            orderIndex = 3,
            requiresApproval = false
        )
        coEvery { agentExecutor.executeStep(taskId, subtaskId) } returns StepExecutionResult(
            status = "failed",
            result = null,
            summary = "Failed to read file",
            durationMs = 500,
            error = "File not found: /path/to/file"
        )
        every { subtaskRepository.findById(subtaskId) } returns subtask
        every {
            chatMessageRepository.create(
                taskId = taskId,
                role = MessageRole.ASSISTANT,
                content = any(),
                metadata = any(),
                tokensIn = any(),
                tokensOut = any(),
                cost = any()
            )
        } returns mockk()

        // When
        val response = agentRouter.executeSubtaskStep(taskId, subtaskId)

        // Then
        assertEquals("failed", response.status)
        assertNotNull(response.error)
        assertEquals("File not found: /path/to/file", response.error)
    }

    @Test
    fun `planSubtaskStep throws when agent executor is unavailable`() {
        val router = AgentRouter(
            agentExecutor = null,
            taskRepository = taskRepository,
            subtaskRepository = subtaskRepository,
            chatMessageRepository = chatMessageRepository,
            configService = configService,
            llmClient = llmClient,
            promptsService = promptsService,
            contextService = contextService,
            projectRoot = Paths.get("D:/test"),
            ideProject = ideProject,
            toolDescriptionBuilder = toolDescriptionBuilder
        )

        val error = assertThrows(IllegalStateException::class.java) {
            router.planSubtaskStep("task-123", "subtask-456")
        }

        assertNotNull(error.message)
    }

    private fun buildSubtask(
        id: String,
        taskId: String,
        orderIndex: Int,
        requiresApproval: Boolean
    ): Subtask {
        val now = System.currentTimeMillis()
        return Subtask(
            id = id,
            taskId = taskId,
            orderIndex = orderIndex,
            kind = SubtaskKind.READ_FILE,
            status = TaskStatus.PENDING,
            description = "Read configuration file",
            paramsJson = null,
            stepPlanJson = null,
            summary = null,
            requiresApproval = requiresApproval,
            approvalStatus = ApprovalStatus.NOT_REQUIRED,
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
            createdAt = now,
            updatedAt = now,
            startedAt = null,
            completedAt = null
        )
    }
}
