package pl.jclab.refio.core.services.orchestration

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.services.PermissionLevel
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolRegistry
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlanModifierTest {

    private lateinit var subtaskRepository: SubtaskRepository
    private lateinit var chatMessageRepository: ChatMessageRepository
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var toolPermissionsService: ToolPermissionsService
    private lateinit var taskRepository: TaskRepository

    private lateinit var modifier: PlanModifier

    @BeforeEach
    fun setup() {
        subtaskRepository = mockk()
        chatMessageRepository = mockk()
        toolRegistry = mockk()
        toolPermissionsService = mockk()
        taskRepository = mockk()

        modifier = PlanModifier(
            subtaskRepository = subtaskRepository,
            chatMessageRepository = chatMessageRepository,
            toolRegistry = toolRegistry,
            toolPermissionsService = toolPermissionsService,
            taskRepository = taskRepository
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

    private fun createMockChatMessage(
        id: String = "msg-${System.nanoTime()}",
        taskId: String = "task-123"
    ) = ChatMessage(
        id = id,
        taskId = taskId,
        role = MessageRole.SYSTEM,
        content = "Test message",
        metadata = null,
        tokensIn = null,
        tokensOut = null,
        cost = null,
        createdAt = System.currentTimeMillis()
    )

    private fun createMockTool(name: String, mode: ToolMode = ToolMode.READ_ONLY): Tool {
        return mockk {
            every { this@mockk.name } returns name
            every { this@mockk.mode } returns mode
        }
    }

    @Nested
    inner class AddSubtaskTests {

        @Test
        fun `should add subtask after specified step`() = runBlocking {
            // Given
            val taskId = "task-123"
            val task = createMockTask(taskId)
            val existingSubtasks = listOf(
                createMockSubtask(id = "sub-1", taskId = taskId, orderIndex = 1),
                createMockSubtask(id = "sub-2", taskId = taskId, orderIndex = 2)
            )
            val newSubtask = createMockSubtask(id = "sub-new", taskId = taskId, orderIndex = 2)

            every { taskRepository.findById(taskId) } returns task
            every { subtaskRepository.findByTaskId(taskId) } returns existingSubtasks
            every { toolRegistry.getTool("read_file") } returns createMockTool("read_file")
            every { toolRegistry.getAllTools() } returns listOf(createMockTool("read_file"))
            every { toolPermissionsService.getPermission("read_file", any(), any()) } returns PermissionLevel.ON
            every { subtaskRepository.createWithShift(any(), any(), any(), any(), any(), any()) } returns newSubtask
            every { chatMessageRepository.create(any(), any(), any(), any(), any(), any(), any()) } returns createMockChatMessage()

            // When
            val result = modifier.addSubtask(
                taskId = taskId,
                afterStep = 1,
                description = "Read additional file",
                kind = "read_file",
                suggestedParams = mapOf("path" to "/test/file.kt")
            )

            // Then
            assertNotNull(result)
            verify { subtaskRepository.createWithShift(taskId, 2, SubtaskKind.READ_FILE, any(), any(), any()) }
            verify { chatMessageRepository.create(taskId, MessageRole.SYSTEM, any(), any(), any(), any(), any()) }
        }

        @Test
        fun `should throw when task not found`() = runBlocking {
            // Given
            val taskId = "non-existent"
            every { taskRepository.findById(taskId) } returns null

            // When/Then
            assertThrows<IllegalArgumentException> {
                modifier.addSubtask(
                    taskId = taskId,
                    afterStep = 1,
                    description = "Test",
                    kind = "read_file",
                    suggestedParams = emptyMap()
                )
            }
        }

        @Test
        fun `should throw when tool not registered`() = runBlocking {
            // Given
            val taskId = "task-123"
            val task = createMockTask(taskId)

            every { taskRepository.findById(taskId) } returns task
            every { toolRegistry.getTool("unknown_tool") } returns null
            every { toolRegistry.getAllTools() } returns listOf(createMockTool("read_file"))

            // When/Then
            val exception = assertThrows<IllegalArgumentException> {
                modifier.addSubtask(
                    taskId = taskId,
                    afterStep = 1,
                    description = "Test",
                    kind = "unknown_tool",
                    suggestedParams = emptyMap()
                )
            }
            assertTrue(exception.message!!.contains("not registered"))
        }

        @Test
        fun `should throw when WRITE tool used in PLAN mode`() = runBlocking {
            // Given
            val taskId = "task-123"
            val task = createMockTask(taskId, mode = TaskMode.PLAN)

            every { taskRepository.findById(taskId) } returns task
            every { toolRegistry.getTool("code_editing") } returns createMockTool("code_editing", ToolMode.WRITE)
            every { toolRegistry.getAllTools() } returns listOf(createMockTool("read_file"))

            // When/Then
            val exception = assertThrows<IllegalArgumentException> {
                modifier.addSubtask(
                    taskId = taskId,
                    afterStep = 1,
                    description = "Edit file",
                    kind = "code_editing",
                    suggestedParams = emptyMap()
                )
            }
            assertTrue(exception.message!!.contains("not allowed"))
        }

        @Test
        fun `should throw when tool is disabled by permissions`() = runBlocking {
            // Given
            val taskId = "task-123"
            val task = createMockTask(taskId)
            val existingSubtasks = listOf(
                createMockSubtask(id = "sub-1", taskId = taskId, orderIndex = 1)
            )

            every { taskRepository.findById(taskId) } returns task
            every { subtaskRepository.findByTaskId(taskId) } returns existingSubtasks
            every { toolRegistry.getTool("code_editing") } returns createMockTool("code_editing", ToolMode.WRITE)
            every { toolRegistry.getAllTools() } returns listOf(createMockTool("read_file"))
            every { toolPermissionsService.getPermission("code_editing", any(), any()) } returns PermissionLevel.OFF

            // When/Then
            val exception = assertThrows<IllegalArgumentException> {
                modifier.addSubtask(
                    taskId = taskId,
                    afterStep = 1,
                    description = "Edit file",
                    kind = "code_editing",
                    suggestedParams = emptyMap()
                )
            }
            assertTrue(exception.message!!.contains("disabled"))
        }

        @Test
        fun `should allow plan_step kind without tool check`() = runBlocking {
            // Given
            val taskId = "task-123"
            val task = createMockTask(taskId)
            val newSubtask = createMockSubtask(id = "sub-new", taskId = taskId, orderIndex = 1)

            every { taskRepository.findById(taskId) } returns task
            every { subtaskRepository.findByTaskId(taskId) } returns emptyList()
            every { subtaskRepository.createWithShift(any(), any(), any(), any(), any(), any()) } returns newSubtask
            every { chatMessageRepository.create(any(), any(), any(), any(), any(), any(), any()) } returns createMockChatMessage()

            // When
            val result = modifier.addSubtask(
                taskId = taskId,
                afterStep = 0,
                description = "Plan step",
                kind = "plan_step",
                suggestedParams = emptyMap()
            )

            // Then
            assertNotNull(result)
        }
    }

    @Nested
    inner class SkipSubtaskTests {

        @Test
        fun `should skip subtask by orderIndex`() = runBlocking {
            // Given
            val taskId = "task-123"
            val subtask = createMockSubtask(id = "sub-1", taskId = taskId, orderIndex = 2)

            every { subtaskRepository.findByTaskId(taskId) } returns listOf(subtask)
            every { subtaskRepository.updateStatus(subtask.id, TaskStatus.CANCELED) } returns subtask
            every { subtaskRepository.updateResult(subtask.id, null, any()) } returns subtask
            every { chatMessageRepository.create(any(), any(), any(), any(), any(), any(), any()) } returns createMockChatMessage()

            // When
            modifier.skipSubtask(taskId, step = 2, reason = "Not needed")

            // Then
            verify { subtaskRepository.updateStatus(subtask.id, TaskStatus.CANCELED) }
            verify { subtaskRepository.updateResult(subtask.id, null, match { it.contains("Not needed") }) }
        }

        @Test
        fun `should throw when step not found`() = runBlocking {
            // Given
            val taskId = "task-123"
            val subtask = createMockSubtask(id = "sub-1", taskId = taskId, orderIndex = 1)

            every { subtaskRepository.findByTaskId(taskId) } returns listOf(subtask)

            // When/Then
            val exception = assertThrows<IllegalArgumentException> {
                modifier.skipSubtask(taskId, step = 999, reason = "Skip")
            }
            assertTrue(exception.message!!.contains("not found"))
        }
    }

    @Nested
    inner class ModifySubtaskTests {

        @Test
        fun `should modify subtask description`() = runBlocking {
            // Given
            val taskId = "task-123"
            val subtask = createMockSubtask(id = "sub-1", taskId = taskId, orderIndex = 1)

            every { subtaskRepository.findByTaskId(taskId) } returns listOf(subtask)
            every { subtaskRepository.update(subtask.id, any(), any()) } returns subtask
            every { chatMessageRepository.create(any(), any(), any(), any(), any(), any(), any()) } returns createMockChatMessage()

            // When
            modifier.modifySubtask(
                taskId = taskId,
                step = 1,
                newDescription = "Updated description",
                newParams = null
            )

            // Then
            verify { subtaskRepository.update(subtask.id, "Updated description", any()) }
            verify { chatMessageRepository.create(taskId, MessageRole.SYSTEM, match { it.contains("description") }, any(), any(), any(), any()) }
        }

        @Test
        fun `should modify subtask parameters`() = runBlocking {
            // Given
            val taskId = "task-123"
            val subtask = createMockSubtask(id = "sub-1", taskId = taskId, orderIndex = 1).copy(
                paramsJson = """{"existing": "value"}"""
            )

            every { subtaskRepository.findByTaskId(taskId) } returns listOf(subtask)
            every { subtaskRepository.update(subtask.id, any(), any()) } returns subtask
            every { chatMessageRepository.create(any(), any(), any(), any(), any(), any(), any()) } returns createMockChatMessage()

            // When
            modifier.modifySubtask(
                taskId = taskId,
                step = 1,
                newDescription = null,
                newParams = mapOf("new_key" to "new_value")
            )

            // Then
            verify {
                subtaskRepository.update(
                    subtask.id,
                    subtask.description,
                    match { it.contains("new_key") && it.contains("modified_by") }
                )
            }
        }

        @Test
        fun `should throw when step not found`() = runBlocking {
            // Given
            val taskId = "task-123"

            every { subtaskRepository.findByTaskId(taskId) } returns emptyList()

            // When/Then
            assertThrows<IllegalArgumentException> {
                modifier.modifySubtask(taskId, step = 1, newDescription = "Test", newParams = null)
            }
        }
    }

    @Nested
    inner class RetrySubtaskTests {

        @Test
        fun `should retry subtask by resetting status`() = runBlocking {
            // Given
            val taskId = "task-123"
            val subtask = createMockSubtask(id = "sub-1", taskId = taskId, orderIndex = 1, status = TaskStatus.FAILED)

            every { subtaskRepository.findByTaskId(taskId) } returns listOf(subtask)
            every { subtaskRepository.updateStatus(subtask.id, TaskStatus.PENDING) } returns subtask
            every { subtaskRepository.updateResult(subtask.id, null, null) } returns subtask
            every { chatMessageRepository.create(any(), any(), any(), any(), any(), any(), any()) } returns createMockChatMessage()

            // When
            modifier.retrySubtask(taskId, step = 1, reason = "Network timeout")

            // Then
            verify { subtaskRepository.updateStatus(subtask.id, TaskStatus.PENDING) }
            verify { subtaskRepository.updateResult(subtask.id, null, null) }
            verify { chatMessageRepository.create(taskId, MessageRole.SYSTEM, match { it.contains("Retrying") }, any(), any(), any(), any()) }
        }

        @Test
        fun `should throw when step not found`() = runBlocking {
            // Given
            val taskId = "task-123"

            every { subtaskRepository.findByTaskId(taskId) } returns emptyList()

            // When/Then
            assertThrows<IllegalArgumentException> {
                modifier.retrySubtask(taskId, step = 1, reason = "Retry")
            }
        }
    }

    @Nested
    inner class KindMappingTests {

        @Test
        fun `should map all known tool kinds`() = runBlocking {
            // This test verifies that kind strings map to correct SubtaskKind enums
            // by attempting to add subtasks with each kind

            val taskId = "task-123"
            val task = createMockTask(taskId)
            val tools = mapOf(
                "read_file" to ToolMode.READ_ONLY,
                "code_editing" to ToolMode.WRITE,
                "create_new_file" to ToolMode.WRITE,
                "multi_edit" to ToolMode.WRITE,
                "read_directory" to ToolMode.READ_ONLY,
                "grep_search" to ToolMode.READ_ONLY,
                "file_search" to ToolMode.READ_ONLY,
                "view_diff" to ToolMode.READ_ONLY
            )

            every { taskRepository.findById(taskId) } returns task
            every { subtaskRepository.findByTaskId(taskId) } returns emptyList()
            every { chatMessageRepository.create(any(), any(), any(), any(), any(), any(), any()) } returns createMockChatMessage()

            tools.forEach { (toolName, toolMode) ->
                every { toolRegistry.getTool(toolName) } returns createMockTool(toolName, toolMode)
                every { toolRegistry.getAllTools() } returns tools.map { createMockTool(it.key, it.value) }
                every { toolPermissionsService.getPermission(toolName, any(), any()) } returns PermissionLevel.ON
            }

            val capturedKinds = mutableListOf<SubtaskKind>()
            every { subtaskRepository.createWithShift(any(), any(), capture(capturedKinds), any(), any(), any()) } answers {
                createMockSubtask(taskId = taskId, orderIndex = 1)
            }

            // When - add subtask for each tool
            tools.keys.forEach { kind ->
                modifier.addSubtask(
                    taskId = taskId,
                    afterStep = 0,
                    description = "Test $kind",
                    kind = kind,
                    suggestedParams = emptyMap()
                )
            }

            // Then - verify all kinds were properly mapped
            assertEquals(tools.size, capturedKinds.size)
            assertTrue(capturedKinds.contains(SubtaskKind.READ_FILE))
            assertTrue(capturedKinds.contains(SubtaskKind.CODE_EDITING))
            assertTrue(capturedKinds.contains(SubtaskKind.CREATE_NEW_FILE))
            assertTrue(capturedKinds.contains(SubtaskKind.GREP_SEARCH))
            assertTrue(capturedKinds.contains(SubtaskKind.FILE_SEARCH))
        }

        @Test
        fun `should map unknown kind to PLAN_STEP`() = runBlocking {
            // Given
            val taskId = "task-123"
            val task = createMockTask(taskId)

            every { taskRepository.findById(taskId) } returns task
            every { subtaskRepository.findByTaskId(taskId) } returns emptyList()
            every { toolRegistry.getTool("some_custom_kind") } returns createMockTool("some_custom_kind")
            every { toolRegistry.getAllTools() } returns listOf(createMockTool("some_custom_kind"))
            every { toolPermissionsService.getPermission("some_custom_kind", any(), any()) } returns PermissionLevel.ON
            every { chatMessageRepository.create(any(), any(), any(), any(), any(), any(), any()) } returns createMockChatMessage()

            var capturedKind: SubtaskKind? = null
            val kindSlot = slot<SubtaskKind>()
            every { subtaskRepository.createWithShift(any(), any(), capture(kindSlot), any(), any(), any()) } answers {
                capturedKind = kindSlot.captured
                createMockSubtask(taskId = taskId, orderIndex = 1)
            }

            // When
            modifier.addSubtask(
                taskId = taskId,
                afterStep = 0,
                description = "Custom step",
                kind = "some_custom_kind",
                suggestedParams = emptyMap()
            )

            // Then
            assertEquals(SubtaskKind.PLAN_STEP, capturedKind)
        }
    }

    @Nested
    inner class ChatNotificationTests {

        @Test
        fun `should save plan change notification for add`() = runBlocking {
            // Given
            val taskId = "task-123"
            val task = createMockTask(taskId)

            every { taskRepository.findById(taskId) } returns task
            every { subtaskRepository.findByTaskId(taskId) } returns emptyList()
            every { toolRegistry.getTool("read_file") } returns createMockTool("read_file")
            every { toolRegistry.getAllTools() } returns listOf(createMockTool("read_file"))
            every { toolPermissionsService.getPermission("read_file", any(), any()) } returns PermissionLevel.ON
            every { subtaskRepository.createWithShift(any(), any(), any(), any(), any(), any()) } returns createMockSubtask()
            every { chatMessageRepository.create(any(), any(), any(), any(), any(), any(), any()) } returns createMockChatMessage()

            // When
            modifier.addSubtask(
                taskId = taskId,
                afterStep = 0,
                description = "New step",
                kind = "read_file",
                suggestedParams = emptyMap()
            )

            // Then
            verify {
                chatMessageRepository.create(
                    taskId = taskId,
                    role = MessageRole.SYSTEM,
                    content = match { it.contains("Added step") },
                    metadata = any(),
                    tokensIn = any(),
                    tokensOut = any(),
                    cost = any()
                )
            }
        }
    }
}
