package pl.jclab.refio.core.workflow

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.core.api.ExecuteStepResponse
import pl.jclab.refio.core.api.PlanningRequest
import pl.jclab.refio.core.api.routers.AgentRouter
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

class WorkflowOrchestratorTest {

    private val intentRouter = mockk<IntentRouter>()
    private val chatService = mockk<ChatService>()
    private val planningService = mockk<PlanningService>()
    private val agentRouter = mockk<AgentRouter>()
    private val subagentRouter = mockk<SubagentRouter>()

    private val orchestrator = WorkflowOrchestrator(
        intentRouter = intentRouter,
        chatService = chatService,
        planningService = planningService,
        agentRouter = agentRouter,
        subagentRouter = subagentRouter,
        userInteraction = null
    )

    @Test
    fun `executes chat intent and returns response`() = kotlinx.coroutines.runBlocking {
        val uiState = UIState(
            taskId = "task-1",
            mode = TaskMode.CHAT,
            executionMode = ExecutionMode.INTERACTIVE,
            input = "hello",
            streamingEnabled = false
        )
        val request = WorkflowRequest(uiState)

        val intent = WorkflowIntent.Chat(
            taskId = "task-1",
            input = "hello",
            contextRefs = emptyList(),
            model = null,
            provider = null
        )
        val response = ChatResponse(
            requestId = "req-1",
            taskId = "task-1",
            messageId = "msg-1",
            output = "ok",
            costs = ChatCosts(tokensIn = 1, tokensOut = 1, usdEst = 0.0)
        )

        coEvery { intentRouter.determineIntent(uiState, any(), any()) } returns intent
        coEvery { chatService.chat(any<ChatRequest>(), any(), any()) } returns response

        val result = orchestrator.execute(request)

        assertEquals(IntentResult.ChatResult(response), result)
        coVerify(exactly = 1) {
            chatService.chat(match { it.taskId == "task-1" && it.input == "hello" }, any(), any())
        }
    }

    @Test
    fun `stops auto loop after executing last step`() = kotlinx.coroutines.runBlocking {
        val uiState = UIState(
            taskId = "task-1",
            mode = TaskMode.AGENT,
            executionMode = ExecutionMode.AUTO,
            input = "continue",
            streamingEnabled = false
        )
        val request = WorkflowRequest(uiState)

        val stepIntent = WorkflowIntent.ExecuteStep(taskId = "task-1", subtaskId = "subtask-1")
        val planIntent = WorkflowIntent.Plan(
            taskId = "task-1",
            input = "continue",
            contextRefs = emptyList(),
            model = null,
            provider = null,
            interactive = false
        )

        coEvery { intentRouter.determineIntent(uiState, any(), any()) } returnsMany listOf(stepIntent, planIntent)
        every {
            agentRouter.executeSubtaskStepWithListener(
                "task-1",
                "subtask-1",
                any<ExecutionEventListener>()
            )
        } returns ExecuteStepResponse(status = "success", summary = "ok", durationMs = 1, error = null)

        val result = orchestrator.execute(request)

        assertEquals("success", (result as IntentResult.StepResult).response.status)
        coVerify(exactly = 1) {
            agentRouter.executeSubtaskStepWithListener("task-1", "subtask-1", any<ExecutionEventListener>())
        }
        coVerify(exactly = 0) { planningService.createPlan(any(), any<PlanningRequest>(), any(), any()) }
    }
}
