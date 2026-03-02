package pl.jclab.refio.testutil

import io.mockk.every
import io.mockk.mockk
import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolMode

/**
 * Factory for creating test mock objects.
 * Provides consistent test data across all tests.
 */
object MockFactory {

    /**
     * Create a mock Task with sensible defaults.
     */
    fun createTask(
        id: String = "task-${System.nanoTime()}",
        name: String = "Test Task",
        mode: TaskMode = TaskMode.CHAT,
        status: TaskStatus = TaskStatus.NEW,
        readOnly: Boolean = false,
        pinned: Boolean = false,
        executionMode: ExecutionMode = ExecutionMode.INTERACTIVE,
        requiresPlanApproval: Boolean = false,
        planApproved: Boolean = false,
        projectId: String = "test-project",
        projectPath: String = "/test/project"
    ) = Task(
        id = id,
        name = name,
        mode = mode,
        status = status,
        readOnly = readOnly,
        pinned = pinned,
        executionMode = executionMode,
        requiresPlanApproval = requiresPlanApproval,
        planApproved = planApproved,
        uiState = null,
        coreApiVersion = "1.0",
        projectId = projectId,
        projectPath = projectPath,
        rate = null,
        tokensIn = 0,
        tokensOut = 0,
        costUsd = 0.0,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    /**
     * Create a mock ChatMessage with sensible defaults.
     */
    fun createChatMessage(
        id: String = "msg-${System.nanoTime()}",
        taskId: String = "task-123",
        role: MessageRole = MessageRole.USER,
        content: String = "Test message"
    ) = ChatMessage(
        id = id,
        taskId = taskId,
        role = role,
        content = content,
        metadata = null,
        toolCalls = null,
        toolCallId = null,
        tokensIn = null,
        tokensOut = null,
        cost = null,
        createdAt = System.currentTimeMillis()
    )

    /**
     * Create a mock read-only Tool.
     */
    fun createReadOnlyTool(
        name: String = "test_read_tool"
    ): Tool = mockk {
        every { this@mockk.name } returns name
        every { mode } returns ToolMode.READ_ONLY
    }

    /**
     * Create a mock write Tool.
     */
    fun createWriteTool(
        name: String = "test_write_tool"
    ): Tool = mockk {
        every { this@mockk.name } returns name
        every { mode } returns ToolMode.WRITE
    }

    /**
     * Create a set of standard tools for testing.
     */
    fun createStandardTools(): List<Tool> = listOf(
        mockk {
            every { name } returns "read_file"
            every { mode } returns ToolMode.READ_ONLY
        },
        mockk {
            every { name } returns "read_directory"
            every { mode } returns ToolMode.READ_ONLY
        },
        mockk {
            every { name } returns "file_search"
            every { mode } returns ToolMode.READ_ONLY
        },
        mockk {
            every { name } returns "grep_search"
            every { mode } returns ToolMode.READ_ONLY
        },
        mockk {
            every { name } returns "view_diff"
            every { mode } returns ToolMode.READ_ONLY
        },
        mockk {
            every { name } returns "create_new_file"
            every { mode } returns ToolMode.WRITE
        },
        mockk {
            every { name } returns "code_editing"
            every { mode } returns ToolMode.WRITE
        },
        mockk {
            every { name } returns "multi_edit"
            every { mode } returns ToolMode.WRITE
        },
        mockk {
            every { name } returns "run_terminal_command"
            every { mode } returns ToolMode.WRITE
        }
    )
}
