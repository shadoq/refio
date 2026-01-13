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
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolResult
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StepPlannerTest {

    private lateinit var taskRepository: TaskRepository
    private lateinit var subtaskRepository: SubtaskRepository
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var llmClient: LLMClient
    private lateinit var promptsService: PromptsService
    private lateinit var toolDescriptionBuilder: ToolDescriptionBuilder
    private lateinit var configService: ConfigService
    private lateinit var toolPermissionsService: ToolPermissionsService

    private lateinit var stepPlanner: StepPlanner

    @BeforeEach
    fun setup() {
        taskRepository = mockk()
        subtaskRepository = mockk()
        toolRegistry = mockk()
        llmClient = mockk()
        promptsService = mockk()
        toolDescriptionBuilder = mockk()
        configService = mockk()
        toolPermissionsService = mockk()

        stepPlanner = StepPlanner(
            taskRepository = taskRepository,
            subtaskRepository = subtaskRepository,
            toolRegistry = toolRegistry,
            llmClient = llmClient,
            promptsService = promptsService,
            toolDescriptionBuilder = toolDescriptionBuilder,
            configService = configService,
            toolPermissionsService = toolPermissionsService,
            contextService = null,
            projectRoot = null
        )

        every { subtaskRepository.countByTaskId(any()) } returns 1L
    }

    private fun createMockTask(
        id: String = "task-123",
        mode: TaskMode = TaskMode.AGENT,
        readOnly: Boolean = false
    ) = Task(
        id = id,
        name = "Test Task",
        mode = mode,
        status = TaskStatus.RUNNING,
        readOnly = readOnly,
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
        kind: SubtaskKind = SubtaskKind.READ_FILE,
        paramsJson: String? = null
    ) = Subtask(
        id = id,
        taskId = taskId,
        orderIndex = orderIndex,
        kind = kind,
        status = TaskStatus.PENDING,
        approvalStatus = ApprovalStatus.PENDING_APPROVAL,
        approvedAt = null,
        requiresApproval = true,
        description = "Read file content",
        paramsJson = paramsJson,
        stepPlanJson = null,
        summary = null,
        result = null,
        errorMessage = null,
        errorStacktrace = null,
        llmModel = null,
        llmProvider = null,
        inputTokens = 0,
        outputTokens = 0,
        costUsd = 0.0,
        latencyMs = 0,
        startedAt = null,
        completedAt = null,
        snapshotIdBeforeWrite = null,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    private fun createMockTool(
        name: String,
        mode: ToolMode = ToolMode.READ_ONLY
    ): Tool = mockk {
        every { this@mockk.name } returns name
        every { this@mockk.description } returns "Test tool"
        every { this@mockk.mode } returns mode
        every { this@mockk.category } returns ToolCategory.DATA_PRODUCING
        every { validateParams(any()) } just Runs
        coEvery { execute(any()) } returns ToolResult(
            success = true,
            output = "Tool output",
            metadata = emptyMap(),
            filesChanged = emptyList()
        )
    }

    @Nested
    inner class GeneratePlanTests {

        @Test
        fun `should generate plan successfully`() = runBlocking {
            // Given
            val taskId = "task-123"
            val subtaskId = "subtask-123"
            val task = createMockTask(taskId)
            val subtask = createMockSubtask(
                id = subtaskId,
                taskId = taskId,
                paramsJson = """{"intent": "Read main file", "tool_type": "read_file", "suggested_params": {"path": "src/main.kt"}}"""
            )

            val llmResponse = """
                {
                    "tool": "read_file",
                    "args": {"path": "src/main.kt"},
                    "reasoning": "Reading the main file as requested"
                }
            """.trimIndent()

            val readFileTool = createMockTool("read_file", ToolMode.READ_ONLY)

            every { taskRepository.findById(taskId) } returns task
            every { taskRepository.incrementMetrics(any(), any(), any(), any()) } returns task
            every { subtaskRepository.findById(subtaskId) } returns subtask
            every { subtaskRepository.findByTaskId(taskId) } returns listOf(subtask)
            every { toolRegistry.getTool("read_file") } returns readFileTool
            every { toolRegistry.getAllTools() } returns listOf(readFileTool)
            every { toolRegistry.getReadOnlyTools() } returns listOf(readFileTool)
            every { toolRegistry.getAvailableTools(any(), any(), any()) } returns listOf(readFileTool)
            every { configService.getModel(any(), any()) } returns Pair("gpt-4", "openai")
            every { configService.getMaxOutputTokens(any()) } returns 4096
            every { configService.get(any()) } returns null
            every { promptsService.getSystemPrompt(any(), any()) } returns "System prompt"
            every { toolDescriptionBuilder.getToolDescriptions(any(), any()) } returns "Tool descriptions"
            every { toolDescriptionBuilder.getValidToolNames(any(), any()) } returns "read_file"
            every { toolPermissionsService.getPermission(any(), any(), any()) } returns PermissionLevel.ON
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
            val result = stepPlanner.generatePlan(taskId, subtaskId)

            // Then
            assertNotNull(result)
            assertEquals("read_file", result.toolCall.name)
            assertEquals("src/main.kt", result.toolCall.params["path"])
            assertNotNull(result.planDecision)
        }

        @Test
        fun `should throw when task not found`() = runBlocking {
            // Given
            val taskId = "non-existent"
            val subtaskId = "subtask-123"

            every { taskRepository.findById(taskId) } returns null

            // When/Then
            assertThrows<IllegalArgumentException> {
                stepPlanner.generatePlan(taskId, subtaskId)
            }
        }

        @Test
        fun `should throw when subtask not found`() = runBlocking {
            // Given
            val taskId = "task-123"
            val subtaskId = "non-existent"
            val task = createMockTask(taskId)

            every { taskRepository.findById(taskId) } returns task
            every { subtaskRepository.findById(subtaskId) } returns null

            // When/Then
            assertThrows<IllegalArgumentException> {
                stepPlanner.generatePlan(taskId, subtaskId)
            }
        }

        @Test
        fun `should throw when subtask belongs to different task`() = runBlocking {
            // Given
            val taskId = "task-123"
            val subtaskId = "subtask-123"
            val task = createMockTask(taskId)
            val subtask = createMockSubtask(id = subtaskId, taskId = "different-task")

            every { taskRepository.findById(taskId) } returns task
            every { subtaskRepository.findById(subtaskId) } returns subtask

            // When/Then
            assertThrows<IllegalArgumentException> {
                stepPlanner.generatePlan(taskId, subtaskId)
            }
        }
    }

    @Nested
    inner class ToolValidationTests {

        @Test
        fun `should reject tool not in allowed list`() = runBlocking {
            // Given
            val taskId = "task-123"
            val subtaskId = "subtask-123"
            val task = createMockTask(taskId)
            val subtask = createMockSubtask(
                id = subtaskId,
                taskId = taskId,
                paramsJson = """{"intent": "Edit file", "tool_type": "code_editing"}"""
            )

            // LLM returns a tool that's not in allowed list
            val llmResponse = """
                {
                    "tool": "dangerous_tool",
                    "args": {"path": "src/main.kt"}
                }
            """.trimIndent()

            val readFileTool = createMockTool("read_file", ToolMode.READ_ONLY)
            val codeEditingTool = createMockTool("code_editing", ToolMode.WRITE)

            every { taskRepository.findById(taskId) } returns task
            every { subtaskRepository.findById(subtaskId) } returns subtask
            every { subtaskRepository.findByTaskId(taskId) } returns listOf(subtask)
            every { subtaskRepository.updateStatus(any(), any()) } returns subtask
            every { subtaskRepository.updateResult(any(), any(), any()) } returns subtask
            every { toolRegistry.getTool("read_file") } returns readFileTool
            every { toolRegistry.getTool("code_editing") } returns codeEditingTool
            every { toolRegistry.getAllTools() } returns listOf(readFileTool, codeEditingTool)
            every { toolRegistry.getAvailableTools(any(), any(), any()) } returns listOf(readFileTool, codeEditingTool)
            every { configService.getModel(any(), any()) } returns Pair("gpt-4", "openai")
            every { configService.getMaxOutputTokens(any()) } returns 4096
            every { configService.get(any()) } returns null
            every { promptsService.getSystemPrompt(any(), any()) } returns "System prompt"
            every { toolDescriptionBuilder.getToolDescriptions(any(), any()) } returns "Tool descriptions"
            every { toolDescriptionBuilder.getValidToolNames(any(), any()) } returns "read_file, code_editing"
            every { toolPermissionsService.getPermission(any(), any(), any()) } returns PermissionLevel.ON
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

            // When/Then
            assertThrows<IllegalStateException> {
                stepPlanner.generatePlan(taskId, subtaskId)
            }
        }

        @Test
        fun `should reject disabled tool`() = runBlocking {
            // Given
            val taskId = "task-123"
            val subtaskId = "subtask-123"
            val task = createMockTask(taskId)
            val subtask = createMockSubtask(
                id = subtaskId,
                taskId = taskId,
                paramsJson = """{"intent": "Run command", "tool_type": "run_terminal_command"}"""
            )

            val llmResponse = """
                {
                    "tool": "run_terminal_command",
                    "args": {"command": "ls"}
                }
            """.trimIndent()

            val readFileTool = createMockTool("read_file", ToolMode.READ_ONLY)
            val terminalTool = createMockTool("run_terminal_command", ToolMode.WRITE)

            every { taskRepository.findById(taskId) } returns task
            every { taskRepository.incrementMetrics(any(), any(), any(), any()) } returns task
            every { subtaskRepository.findById(subtaskId) } returns subtask
            every { subtaskRepository.findByTaskId(taskId) } returns listOf(subtask)
            every { toolRegistry.getTool("read_file") } returns readFileTool
            every { toolRegistry.getTool("run_terminal_command") } returns terminalTool
            every { toolRegistry.getAllTools() } returns listOf(readFileTool, terminalTool)
            every { toolRegistry.getAvailableTools(any(), any(), any()) } returns listOf(readFileTool, terminalTool)
            every { configService.getModel(any(), any()) } returns Pair("gpt-4", "openai")
            every { configService.getMaxOutputTokens(any()) } returns 4096
            every { configService.get(any()) } returns null
            every { promptsService.getSystemPrompt(any(), any()) } returns "System prompt"
            every { toolDescriptionBuilder.getToolDescriptions(any(), any()) } returns "Tool descriptions"
            every { toolDescriptionBuilder.getValidToolNames(any(), any()) } returns "read_file, run_terminal_command"
            every { toolPermissionsService.getPermission("read_file", any(), any()) } returns PermissionLevel.ON
            every { toolPermissionsService.getPermission("run_terminal_command", any(), any()) } returns PermissionLevel.OFF
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

            // When/Then
            assertThrows<SecurityException> {
                stepPlanner.generatePlan(taskId, subtaskId)
            }
        }
    }

    @Nested
    inner class ToolModeRestrictionTests {

        @Test
        fun `PLAN mode should only allow READ_ONLY tools`() = runBlocking {
            // Given
            val taskId = "task-123"
            val subtaskId = "subtask-123"
            val task = createMockTask(taskId, TaskMode.PLAN)
            val subtask = createMockSubtask(
                id = subtaskId,
                taskId = taskId,
                paramsJson = """{"intent": "Read file", "tool_type": "read_file"}"""
            )

            val llmResponse = """
                {
                    "tool": "read_file",
                    "args": {"path": "src/main.kt"}
                }
            """.trimIndent()

            val readFileTool = createMockTool("read_file", ToolMode.READ_ONLY)

            every { taskRepository.findById(taskId) } returns task
            every { taskRepository.incrementMetrics(any(), any(), any(), any()) } returns task
            every { subtaskRepository.findById(subtaskId) } returns subtask
            every { subtaskRepository.findByTaskId(taskId) } returns listOf(subtask)
            every { toolRegistry.getTool("read_file") } returns readFileTool
            every { toolRegistry.getAllTools() } returns listOf(readFileTool)
            every { toolRegistry.getReadOnlyTools() } returns listOf(readFileTool)
            every { toolRegistry.getAvailableTools(any(), any(), any()) } returns listOf(readFileTool)
            every { configService.getModel(any(), any()) } returns Pair("gpt-4", "openai")
            every { configService.getMaxOutputTokens(any()) } returns 4096
            every { configService.get(any()) } returns null
            every { promptsService.getSystemPrompt(any(), any()) } returns "System prompt"
            every { toolDescriptionBuilder.getToolDescriptions(any(), any()) } returns "Tool descriptions"
            every { toolDescriptionBuilder.getValidToolNames(any(), any()) } returns "read_file"
            every { toolPermissionsService.getPermission(any(), any(), any()) } returns PermissionLevel.ON
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
            val result = stepPlanner.generatePlan(taskId, subtaskId)

            // Then
            assertNotNull(result)
            assertEquals("read_file", result.toolCall.name)
        }

        @Test
        fun `AGENT mode should allow WRITE tools`() = runBlocking {
            // Given
            val taskId = "task-123"
            val subtaskId = "subtask-123"
            val task = createMockTask(taskId, TaskMode.AGENT)
            val subtask = createMockSubtask(
                id = subtaskId,
                taskId = taskId,
                kind = SubtaskKind.CODE_EDITING,
                paramsJson = """{"intent": "Edit file", "tool_type": "code_editing", "suggested_params": {"path": "src/main.kt"}}"""
            )

            val llmResponse = """
                {
                    "tool": "code_editing",
                    "args": {"path": "src/main.kt", "old_string": "old", "new_string": "new"}
                }
            """.trimIndent()

            val codeEditingTool = createMockTool("code_editing", ToolMode.WRITE)
            val readFileTool = createMockTool("read_file", ToolMode.READ_ONLY)

            every { taskRepository.findById(taskId) } returns task
            every { taskRepository.incrementMetrics(any(), any(), any(), any()) } returns task
            every { subtaskRepository.findById(subtaskId) } returns subtask
            every { subtaskRepository.findByTaskId(taskId) } returns listOf(subtask)
            every { toolRegistry.getTool("code_editing") } returns codeEditingTool
            every { toolRegistry.getTool("read_file") } returns readFileTool
            every { toolRegistry.getAllTools() } returns listOf(codeEditingTool, readFileTool)
            every { toolRegistry.getAvailableTools(any(), any(), any()) } returns listOf(codeEditingTool, readFileTool)
            every { configService.getModel(any(), any()) } returns Pair("gpt-4", "openai")
            every { configService.getMaxOutputTokens(any()) } returns 4096
            every { configService.get(any()) } returns null
            every { promptsService.getSystemPrompt(any(), any()) } returns "System prompt"
            every { toolDescriptionBuilder.getToolDescriptions(any(), any()) } returns "Tool descriptions"
            every { toolDescriptionBuilder.getValidToolNames(any(), any()) } returns "code_editing"
            every { toolPermissionsService.getPermission(any(), any(), any()) } returns PermissionLevel.ON
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
            val result = stepPlanner.generatePlan(taskId, subtaskId)

            // Then
            assertNotNull(result)
            assertEquals("code_editing", result.toolCall.name)
        }
    }

    @Nested
    inner class PlanDecisionTests {

        @Test
        fun `should track when LLM modifies suggested tool`() = runBlocking {
            // Given
            val taskId = "task-123"
            val subtaskId = "subtask-123"
            val task = createMockTask(taskId)
            val subtask = createMockSubtask(
                id = subtaskId,
                taskId = taskId,
                paramsJson = """{"intent": "Search files", "tool_type": "file_search", "suggested_params": {"pattern": "*.kt"}}"""
            )

            // LLM changes tool from file_search to grep_search
            val llmResponse = """
                {
                    "tool": "grep_search",
                    "args": {"pattern": "class.*Service", "path": "src"},
                    "reasoning": "grep_search is more appropriate for finding class definitions"
                }
            """.trimIndent()

            val fileSearchTool = createMockTool("file_search", ToolMode.READ_ONLY)
            val grepSearchTool = createMockTool("grep_search", ToolMode.READ_ONLY)

            every { taskRepository.findById(taskId) } returns task
            every { taskRepository.incrementMetrics(any(), any(), any(), any()) } returns task
            every { subtaskRepository.findById(subtaskId) } returns subtask
            every { subtaskRepository.findByTaskId(taskId) } returns listOf(subtask)
            every { toolRegistry.getTool("file_search") } returns fileSearchTool
            every { toolRegistry.getTool("grep_search") } returns grepSearchTool
            every { toolRegistry.getAllTools() } returns listOf(fileSearchTool, grepSearchTool)
            every { toolRegistry.getAvailableTools(any(), any(), any()) } returns listOf(fileSearchTool, grepSearchTool)
            every { configService.getModel(any(), any()) } returns Pair("gpt-4", "openai")
            every { configService.getMaxOutputTokens(any()) } returns 4096
            every { configService.get(any()) } returns null
            every { promptsService.getSystemPrompt(any(), any()) } returns "System prompt"
            every { toolDescriptionBuilder.getToolDescriptions(any(), any()) } returns "Tool descriptions"
            every { toolDescriptionBuilder.getValidToolNames(any(), any()) } returns "file_search, grep_search"
            every { toolPermissionsService.getPermission(any(), any(), any()) } returns PermissionLevel.ON
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
            val result = stepPlanner.generatePlan(taskId, subtaskId)

            // Then
            assertNotNull(result)
            assertEquals("grep_search", result.toolCall.name)
            assertTrue(result.planDecision.wasModified)
            assertEquals("file_search", result.planDecision.suggestedTool)
            assertEquals("grep_search", result.planDecision.selectedTool)
        }
    }
}
