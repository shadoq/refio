package pl.jclab.refio.core.workflow

import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.core.db.ApprovalStatus
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.workflow.models.UIState
import pl.jclab.refio.core.workflow.models.WorkflowIntent

class IntentRouterTest {

    private val subtaskRepository = mockk<SubtaskRepository>()
    private val subagentRouter = mockk<SubagentRouter>()

    private val router = IntentRouter(
        subtaskRepository = subtaskRepository,
        subagentRouter = subagentRouter
    )

    @Test
    fun `returns subagent intent for subagent invocation`() = runBlocking {
        val uiState = UIState(
            taskId = "task-1",
            mode = TaskMode.AGENT,
            executionMode = ExecutionMode.INTERACTIVE,
            input = "!security-reviewer check",
            contextRefs = emptyList(),
            model = null,
            provider = null
        )

        every { subagentRouter.parseSubagentInvocation(uiState.input) } returns ("security-reviewer" to "check")

        val intent = router.determineIntent(uiState)

        assertIs<WorkflowIntent.Subagent>(intent)
        assertEquals("task-1", intent.taskId)
        assertEquals("security-reviewer", intent.name)
        assertEquals("check", intent.prompt)
    }

    @Test
    fun `returns chat intent for chat mode`() = runBlocking {
        val uiState = UIState(
            taskId = "task-1",
            mode = TaskMode.CHAT,
            executionMode = ExecutionMode.INTERACTIVE,
            input = "hello",
            contextRefs = emptyList(),
            model = null,
            provider = null
        )

        every { subagentRouter.parseSubagentInvocation(uiState.input) } returns null

        val intent = router.determineIntent(uiState)

        assertIs<WorkflowIntent.Chat>(intent)
        assertEquals("task-1", intent.taskId)
        assertEquals("hello", intent.input)
    }

    @Test
    fun `returns execute step intent when pending subtasks exist`() = runBlocking {
        val uiState = UIState(
            taskId = "task-1",
            mode = TaskMode.AGENT,
            executionMode = ExecutionMode.AUTO,
            input = "continue",
            contextRefs = emptyList(),
            model = null,
            provider = null
        )

        every { subagentRouter.parseSubagentInvocation(uiState.input) } returns null
        every { subtaskRepository.findByTaskId("task-1") } returns listOf(
            createSubtask("subtask-1", 2, TaskStatus.PENDING),
            createSubtask("subtask-2", 1, TaskStatus.PLANNED)
        )

        val intent = router.determineIntent(uiState)

        assertIs<WorkflowIntent.ExecuteStep>(intent)
        assertEquals("task-1", intent.taskId)
        assertEquals("subtask-2", intent.subtaskId)
    }

    @Test
    fun `returns plan intent when no pending subtasks`() = runBlocking {
        val uiState = UIState(
            taskId = "task-1",
            mode = TaskMode.PLAN,
            executionMode = ExecutionMode.INTERACTIVE,
            input = "plan",
            contextRefs = emptyList(),
            model = null,
            provider = null
        )

        every { subagentRouter.parseSubagentInvocation(uiState.input) } returns null
        every { subtaskRepository.findByTaskId("task-1") } returns emptyList()

        val intent = router.determineIntent(uiState)

        assertIs<WorkflowIntent.Plan>(intent)
        assertEquals("task-1", intent.taskId)
        assertEquals(true, intent.interactive)
    }

    private fun createSubtask(id: String, orderIndex: Int, status: TaskStatus): Subtask {
        return Subtask(
            id = id,
            taskId = "task-1",
            orderIndex = orderIndex,
            kind = SubtaskKind.PLAN_STEP,
            status = status,
            description = "test",
            paramsJson = null,
            stepPlanJson = null,
            summary = null,
            requiresApproval = false,
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
            createdAt = 0,
            updatedAt = 0,
            startedAt = null,
            completedAt = null
        )
    }
}
