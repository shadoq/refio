package pl.jclab.refio.core.api.routers

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import pl.jclab.refio.core.api.PlanningRequest
import pl.jclab.refio.core.api.PlanningResponse
import pl.jclab.refio.core.api.PlanCost
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.services.PlanningService

class PlanningRouterTest {

    private lateinit var planningService: PlanningService
    private lateinit var subtaskRepository: SubtaskRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var planningRouter: PlanningRouter

    @BeforeEach
    fun setup() {
        planningService = mockk()
        subtaskRepository = mockk()
        taskRepository = mockk()
        planningRouter = PlanningRouter(planningService, subtaskRepository, taskRepository)
    }

    @Test
    fun `plan generates execution plan from request`() = runBlocking {
        // Given
        val taskId = "task-123"
        val request = PlanningRequest(
            input = "Create a user service",
            contextRefs = emptyList(),
            model = null,
            provider = null,
            interactive = true
        )
        val expectedResponse = PlanningResponse(
            plan = "Step 1: Create interface\nStep 2: Implement service",
            subtasks = emptyList(),
            costs = PlanCost(tokensIn = 100, tokensOut = 200, usdEst = 0.001),
            modelUsed = "gpt-4",
            providerUsed = "openai"
        )
        coEvery { planningService.createPlan(taskId, request, false, null) } returns expectedResponse

        // When
        val response = planningRouter.plan(taskId, request, stream = false, onChunk = null)

        // Then
        assertEquals(expectedResponse, response)
        coVerify { planningService.createPlan(taskId, request, false, null) }
    }

    @Test
    fun `plan with streaming calls onChunk callback`() = runBlocking {
        // Given
        val taskId = "task-123"
        val request = PlanningRequest(
            input = "Create a user service",
            contextRefs = emptyList(),
            model = null,
            provider = null,
            interactive = true
        )
        val expectedResponse = PlanningResponse(
            plan = "Generated plan",
            subtasks = emptyList(),
            costs = PlanCost(tokensIn = 100, tokensOut = 200, usdEst = 0.001),
            modelUsed = "gpt-4",
            providerUsed = "openai"
        )
        val onChunk = mockk<(pl.jclab.refio.core.api.StreamChunk) -> Unit>(relaxed = true)
        coEvery { planningService.createPlan(taskId, request, true, any()) } returns expectedResponse

        // When
        val response = planningRouter.plan(taskId, request, stream = true, onChunk = onChunk)

        // Then
        assertEquals(expectedResponse, response)
        coVerify { planningService.createPlan(taskId, request, true, any()) }
    }
}
