package pl.jclab.refio.core.services

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentPlanServiceTest {

    private lateinit var service: AgentPlanService

    @BeforeEach
    fun setup() {
        service = AgentPlanService()
    }

    @Test
    fun `setPlan creates plan and getPlan retrieves it`() {
        val steps = listOf(
            AgentPlanStep(0, "Search emails"),
            AgentPlanStep(1, "Analyze results"),
            AgentPlanStep(2, "Write report")
        )
        service.setPlan("task-1", null, steps)

        val plan = service.getPlan("task-1")
        assertNotNull(plan)
        assertEquals(3, plan.size)
        assertEquals("Search emails", plan[0].title)
        assertEquals(PlanStepStatus.PENDING, plan[0].status)
    }

    @Test
    fun `setPlan with agentId scopes correctly`() {
        val mainSteps = listOf(AgentPlanStep(0, "Main step"))
        val agentSteps = listOf(AgentPlanStep(0, "Agent step"))

        service.setPlan("task-1", null, mainSteps)
        service.setPlan("task-1", "searcher", agentSteps)

        assertEquals("Main step", service.getPlan("task-1")!![0].title)
        assertEquals("Agent step", service.getPlan("task-1", "searcher")!![0].title)
    }

    @Test
    fun `updateStep changes status and note`() {
        service.setPlan("task-1", null, listOf(
            AgentPlanStep(0, "Step one"),
            AgentPlanStep(1, "Step two")
        ))

        val updated = service.updateStep("task-1", null, 0, PlanStepStatus.COMPLETED, "Done!")
        assertNotNull(updated)
        assertEquals(PlanStepStatus.COMPLETED, updated.status)
        assertEquals("Done!", updated.note)
    }

    @Test
    fun `updateStep returns null for invalid index`() {
        service.setPlan("task-1", null, listOf(AgentPlanStep(0, "Only step")))
        assertNull(service.updateStep("task-1", null, 5, PlanStepStatus.COMPLETED, null))
    }

    @Test
    fun `updateStep returns null for missing task`() {
        assertNull(service.updateStep("nonexistent", null, 0, PlanStepStatus.COMPLETED, null))
    }

    @Test
    fun `getAllPlansForTask returns all scoped plans`() {
        service.setPlan("task-1", null, listOf(AgentPlanStep(0, "Main")))
        service.setPlan("task-1", "searcher", listOf(AgentPlanStep(0, "Search")))
        service.setPlan("task-1", "writer", listOf(AgentPlanStep(0, "Write")))
        service.setPlan("task-2", null, listOf(AgentPlanStep(0, "Other task")))

        val plans = service.getAllPlansForTask("task-1")
        assertEquals(3, plans.size)
        assertTrue(plans.containsKey("task-1"))
        assertTrue(plans.containsKey("task-1:searcher"))
        assertTrue(plans.containsKey("task-1:writer"))
    }

    @Test
    fun `buildPlanSection returns empty string when no plans`() {
        assertEquals("", service.buildPlanSection("task-1"))
    }

    @Test
    fun `buildPlanSection renders formatted plan`() {
        service.setPlan("task-1", null, listOf(
            AgentPlanStep(0, "First step", status = PlanStepStatus.COMPLETED, note = "OK"),
            AgentPlanStep(1, "Second step", status = PlanStepStatus.IN_PROGRESS)
        ))

        val section = service.buildPlanSection("task-1")
        assertTrue(section.contains("<agent_plans>"))
        assertTrue(section.contains("</agent_plans>"))
        assertTrue(section.contains("[x] 1. First step"))
        assertTrue(section.contains("[>] 2. Second step"))
        assertTrue(section.contains("Note: OK"))
    }

    @Test
    fun `clear removes all plans`() {
        service.setPlan("task-1", null, listOf(AgentPlanStep(0, "Step")))
        service.clear()
        assertNull(service.getPlan("task-1"))
    }

    @Test
    fun `PlanStepStatus fromString handles various formats`() {
        assertEquals(PlanStepStatus.COMPLETED, PlanStepStatus.fromString("completed"))
        assertEquals(PlanStepStatus.COMPLETED, PlanStepStatus.fromString("COMPLETED"))
        assertEquals(PlanStepStatus.IN_PROGRESS, PlanStepStatus.fromString("in_progress"))
        assertEquals(PlanStepStatus.IN_PROGRESS, PlanStepStatus.fromString("inprogress"))
        assertNull(PlanStepStatus.fromString("invalid"))
    }
}
