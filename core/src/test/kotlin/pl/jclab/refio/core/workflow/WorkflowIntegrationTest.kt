package pl.jclab.refio.core.workflow

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.core.api.ExecuteStepResponse
import pl.jclab.refio.core.api.PlanningRequest
import pl.jclab.refio.core.api.routers.AgentRouter
import pl.jclab.refio.core.db.ApprovalStatus
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.models.api.ChatCosts
import pl.jclab.refio.core.models.api.ChatRequest
import pl.jclab.refio.core.models.api.ChatResponse
import pl.jclab.refio.core.services.ChatService
import pl.jclab.refio.core.services.PlanningService
import pl.jclab.refio.core.services.execution.unified.ExecutionEventListener
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.UIState
import pl.jclab.refio.core.workflow.models.WorkflowIntent
import pl.jclab.refio.core.workflow.models.WorkflowRequest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkflowIntegrationTest {

    // IntentRouter dependencies
    private val subtaskRepository = mockk<SubtaskRepository>()
    private val subagentRouter = mockk<SubagentRouter>()

    // WorkflowOrchestrator dependencies (now services, not executors)
    private val chatService = mockk<ChatService>()
    private val planningService = mockk<PlanningService>()
    private val agentRouter = mockk<AgentRouter>()

    private lateinit var intentRouter: IntentRouter
    private lateinit var orchestrator: WorkflowOrchestrator

    @BeforeEach
    fun setup() {
        intentRouter = IntentRouter(
            subtaskRepository = subtaskRepository,
            subagentRouter = subagentRouter
        )

        orchestrator = WorkflowOrchestrator(
            intentRouter = intentRouter,
            chatService = chatService,
            planningService = planningService,
            agentRouter = agentRouter,
            subagentRouter = subagentRouter,
            userInteraction = null
        )
    }

    private fun createSubtask(id: String, orderIndex: Int, status: TaskStatus): Subtask {
        return Subtask(
            id = id,
            taskId = "task-1",
            orderIndex = orderIndex,
            kind = SubtaskKind.PLAN_STEP,
            status = status,
            description = "test subtask",
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

    @Nested
    inner class IntentRoutingTests {

        @Test
        fun `chat mode routes to chat intent`() = runBlocking {
            // Given
            val uiState = UIState(
                taskId = "task-1",
                mode = TaskMode.CHAT,
                executionMode = ExecutionMode.INTERACTIVE,
                input = "explain this code"
            )
            every { subagentRouter.parseSubagentInvocation("explain this code") } returns null

            // When
            val intent = intentRouter.determineIntent(uiState)

            // Then
            assertIs<WorkflowIntent.Chat>(intent)
            assertEquals("task-1", intent.taskId)
            assertEquals("explain this code", intent.input)
        }

        @Test
        fun `plan mode routes to plan intent`() = runBlocking {
            // Given
            val uiState = UIState(
                taskId = "task-1",
                mode = TaskMode.PLAN,
                executionMode = ExecutionMode.INTERACTIVE,
                input = "analyze the architecture"
            )
            every { subagentRouter.parseSubagentInvocation("analyze the architecture") } returns null
            every { subtaskRepository.findByTaskId("task-1") } returns emptyList()

            // When
            val intent = intentRouter.determineIntent(uiState)

            // Then
            assertIs<WorkflowIntent.Plan>(intent)
            assertEquals("task-1", intent.taskId)
            assertTrue(intent.interactive)
        }

        @Test
        fun `agent mode with no pending subtasks routes to plan`() = runBlocking {
            // Given
            val uiState = UIState(
                taskId = "task-1",
                mode = TaskMode.AGENT,
                executionMode = ExecutionMode.INTERACTIVE,
                input = "refactor this module"
            )
            every { subagentRouter.parseSubagentInvocation("refactor this module") } returns null
            every { subtaskRepository.findByTaskId("task-1") } returns emptyList()

            // When
            val intent = intentRouter.determineIntent(uiState)

            // Then
            assertIs<WorkflowIntent.Plan>(intent)
        }

        @Test
        fun `agent auto mode with pending subtasks routes to execute step`() = runBlocking {
            // Given
            val uiState = UIState(
                taskId = "task-1",
                mode = TaskMode.AGENT,
                executionMode = ExecutionMode.AUTO,
                input = "continue"
            )
            every { subagentRouter.parseSubagentInvocation("continue") } returns null
            every { subtaskRepository.findByTaskId("task-1") } returns listOf(
                createSubtask("s1", 1, TaskStatus.PLANNED),
                createSubtask("s2", 2, TaskStatus.PENDING)
            )

            // When
            val intent = intentRouter.determineIntent(uiState)

            // Then
            assertIs<WorkflowIntent.ExecuteStep>(intent)
            assertEquals("s1", intent.subtaskId) // lowest orderIndex PLANNED subtask
        }

        @Test
        fun `subagent invocation overrides mode`() = runBlocking {
            // Given
            val uiState = UIState(
                taskId = "task-1",
                mode = TaskMode.CHAT,
                executionMode = ExecutionMode.INTERACTIVE,
                input = "!security-reviewer check"
            )
            every {
                subagentRouter.parseSubagentInvocation("!security-reviewer check")
            } returns ("security-reviewer" to "check")

            // When
            val intent = intentRouter.determineIntent(uiState)

            // Then
            assertIs<WorkflowIntent.Subagent>(intent)
            assertEquals("security-reviewer", intent.name)
            assertEquals("check", intent.prompt)
        }
    }

    @Nested
    inner class ChatFlowEndToEndTests {

        @Test
        fun `complete chat flow with mock LLM`() = runBlocking {
            // Given
            val uiState = UIState(
                taskId = "task-1",
                mode = TaskMode.CHAT,
                executionMode = ExecutionMode.INTERACTIVE,
                input = "What is dependency injection?",
                streamingEnabled = false
            )
            val request = WorkflowRequest(uiState)

            every {
                subagentRouter.parseSubagentInvocation("What is dependency injection?")
            } returns null

            val chatResponse = ChatResponse(
                requestId = "req-1",
                taskId = "task-1",
                messageId = "msg-1",
                output = "Dependency injection is a design pattern...",
                costs = ChatCosts(tokensIn = 50, tokensOut = 100, usdEst = 0.001)
            )

            // Orchestrator now calls chatService.chat directly.
            coEvery { chatService.chat(any<ChatRequest>(), any(), any()) } returns chatResponse

            // When
            val result = orchestrator.execute(request)

            // Then
            assertIs<IntentResult.ChatResult>(result)
            assertEquals("Dependency injection is a design pattern...", result.response.output)
            assertEquals(50, result.response.costs.tokensIn)
            assertEquals(100, result.response.costs.tokensOut)

            coVerify(exactly = 1) {
                chatService.chat(match<ChatRequest> { it.taskId == "task-1" }, any(), any())
            }
        }

        @Test
        fun `chat flow preserves task id and context`() = runBlocking {
            // Given
            val uiState = UIState(
                taskId = "task-42",
                mode = TaskMode.CHAT,
                executionMode = ExecutionMode.INTERACTIVE,
                input = "hello",
                streamingEnabled = false
            )
            val request = WorkflowRequest(uiState)

            every { subagentRouter.parseSubagentInvocation("hello") } returns null

            val response = ChatResponse(
                requestId = "req-1",
                taskId = "task-42",
                messageId = "msg-1",
                output = "hi",
                costs = ChatCosts(tokensIn = 5, tokensOut = 5, usdEst = 0.0)
            )
            coEvery {
                chatService.chat(match<ChatRequest> { it.taskId == "task-42" }, any(), any())
            } returns response

            // When
            val result = orchestrator.execute(request)

            // Then
            assertIs<IntentResult.ChatResult>(result)
            assertEquals("task-42", result.response.taskId)
        }
    }

    @Nested
    inner class StepExecutionFlowTests {

        @Test
        fun `auto mode executes step and stops`() = runBlocking {
            // Given
            val uiState = UIState(
                taskId = "task-1",
                mode = TaskMode.AGENT,
                executionMode = ExecutionMode.AUTO,
                input = "continue",
                streamingEnabled = false
            )
            val request = WorkflowRequest(uiState)

            // First call: step execution
            every { subagentRouter.parseSubagentInvocation("continue") } returns null
            every { subtaskRepository.findByTaskId("task-1") } returnsMany listOf(
                listOf(createSubtask("s1", 1, TaskStatus.PLANNED)),
                emptyList()  // second call: no more subtasks
            )

            val stepResponse = ExecuteStepResponse(
                status = "success",
                summary = "Step completed",
                durationMs = 500,
                error = null
            )
            every {
                agentRouter.executeSubtaskStepWithListener(
                    "task-1",
                    "s1",
                    any<ExecutionEventListener>()
                )
            } returns stepResponse

            // When
            val result = orchestrator.execute(request)

            // Then
            assertIs<IntentResult.StepResult>(result)
            assertEquals("success", result.response.status)
            assertEquals("Step completed", result.response.summary)

            // Should execute only one step and stop (AUTO stops after plan intent follows)
            coVerify(exactly = 1) {
                agentRouter.executeSubtaskStepWithListener("task-1", "s1", any<ExecutionEventListener>())
            }
        }
    }
}
