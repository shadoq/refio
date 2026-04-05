package pl.jclab.refio.core.tools.implementations

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.services.AgentPlanService
import pl.jclab.refio.core.services.PlanStepStatus
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TasksToolTest {

    private lateinit var planService: AgentPlanService
    private lateinit var tool: TasksTool

    @BeforeEach
    fun setup() {
        planService = AgentPlanService()
        tool = TasksTool(planService)
    }

    @Test
    fun `tasks tool has correct metadata`() {
        assertEquals("tasks", tool.name)
        assertEquals(ToolMode.READ_ONLY, tool.mode)
        assertEquals(ToolCategory.SYSTEM, tool.category)
    }

    @Test
    fun `plan action creates plan with steps`() = runBlocking {
        val result = tool.execute(mapOf(
            "_task_id" to "task-1",
            "action" to "plan",
            "steps" to listOf(
                mapOf("title" to "Search", "description" to "Search for emails"),
                mapOf("title" to "Analyze"),
                mapOf("title" to "Report")
            )
        ))

        assertTrue(result.success)
        assertTrue(result.output!!.contains("3 steps"))
        assertEquals(3, result.metadata!!["plan_steps"])

        val plan = planService.getPlan("task-1")
        assertNotNull(plan)
        assertEquals(3, plan.size)
        assertEquals("Search", plan[0].title)
        assertEquals("Search for emails", plan[0].description)
    }

    @Test
    fun `plan action fails without task_id`() = runBlocking {
        val result = tool.execute(mapOf(
            "action" to "plan",
            "steps" to listOf(mapOf("title" to "Step"))
        ))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("task context"))
    }

    @Test
    fun `plan action fails without steps`() = runBlocking {
        val result = tool.execute(mapOf(
            "_task_id" to "task-1",
            "action" to "plan"
        ))
        assertFalse(result.success)
    }

    @Test
    fun `update action marks step completed`() = runBlocking {
        tool.execute(mapOf(
            "_task_id" to "task-1",
            "action" to "plan",
            "steps" to listOf(mapOf("title" to "Step A"), mapOf("title" to "Step B"))
        ))

        val result = tool.execute(mapOf(
            "_task_id" to "task-1",
            "action" to "update",
            "step_index" to 0,
            "status" to "completed",
            "note" to "Found 5 results"
        ))

        assertTrue(result.success)
        assertTrue(result.output!!.contains("completed"))
        assertTrue(result.output!!.contains("1/2"))

        val plan = planService.getPlan("task-1")!!
        assertEquals(PlanStepStatus.COMPLETED, plan[0].status)
        assertEquals("Found 5 results", plan[0].note)
    }

    @Test
    fun `update action fails with invalid status`() = runBlocking {
        tool.execute(mapOf(
            "_task_id" to "task-1",
            "action" to "plan",
            "steps" to listOf(mapOf("title" to "Step"))
        ))

        val result = tool.execute(mapOf(
            "_task_id" to "task-1",
            "action" to "update",
            "step_index" to 0,
            "status" to "garbage"
        ))
        assertFalse(result.success)
    }

    @Test
    fun `list action shows empty plan message`() = runBlocking {
        val result = tool.execute(mapOf(
            "_task_id" to "task-1",
            "action" to "list"
        ))
        assertTrue(result.success)
        assertTrue(result.output!!.contains("No plan"))
    }

    @Test
    fun `list action shows plan status`() = runBlocking {
        tool.execute(mapOf(
            "_task_id" to "task-1",
            "action" to "plan",
            "steps" to listOf(mapOf("title" to "Done step"), mapOf("title" to "Pending step"))
        ))
        planService.updateStep("task-1", null, 0, PlanStepStatus.COMPLETED, null)

        val result = tool.execute(mapOf(
            "_task_id" to "task-1",
            "action" to "list"
        ))
        assertTrue(result.success)
        assertTrue(result.output!!.contains("[x] 1. Done step"))
        assertTrue(result.output!!.contains("[ ] 2. Pending step"))
        assertTrue(result.output!!.contains("1/2"))
    }

    @Test
    fun `fails without action`() = runBlocking {
        val result = tool.execute(mapOf("_task_id" to "task-1"))
        assertFalse(result.success)
    }
}
