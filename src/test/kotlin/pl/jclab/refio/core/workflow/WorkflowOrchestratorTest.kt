package pl.jclab.refio.core.workflow

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.core.api.ExecuteStepResponse
import pl.jclab.refio.core.models.api.ChatCosts
import pl.jclab.refio.core.models.api.ChatResponse
import pl.jclab.refio.core.workflow.executors.ChatExecutor
import pl.jclab.refio.core.workflow.executors.PlanExecutor
import pl.jclab.refio.core.workflow.executors.StepExecutor
import pl.jclab.refio.core.workflow.executors.SubagentExecutor
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.UIState
import pl.jclab.refio.core.workflow.models.WorkflowIntent
import pl.jclab.refio.core.workflow.models.WorkflowRequest

class WorkflowOrchestratorTest {

    private val intentRouter = mockk<IntentRouter>()
    private val chatExecutor = mockk<ChatExecutor>()
    private val planExecutor = mockk<PlanExecutor>()
    private val stepExecutor = mockk<StepExecutor>()
    private val subagentExecutor = mockk<SubagentExecutor>()

    private val orchestrator = WorkflowOrchestrator(
        intentRouter = intentRouter,
        chatExecutor = chatExecutor,
        planExecutor = planExecutor,
        stepExecutor = stepExecutor,
        subagentExecutor = subagentExecutor,
        userInteraction = null,
        singleToolExecutor = null
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
        coEvery { chatExecutor.execute(intent, any(), any()) } returns IntentResult.ChatResult(response)

        val result = orchestrator.execute(request)

        assertEquals(IntentResult.ChatResult(response), result)
        coVerify(exactly = 1) { chatExecutor.execute(intent, any(), any()) }
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
        coEvery { stepExecutor.execute(stepIntent, any(), any()) } returns IntentResult.StepResult(
            ExecuteStepResponse(status = "success", summary = "ok", durationMs = 1, error = null)
        )

        val result = orchestrator.execute(request)

        assertEquals("success", (result as IntentResult.StepResult).response.status)
        coVerify(exactly = 1) { stepExecutor.execute(stepIntent, any(), any()) }
        coVerify(exactly = 0) { planExecutor.execute(any(), any(), any()) }
    }
}
