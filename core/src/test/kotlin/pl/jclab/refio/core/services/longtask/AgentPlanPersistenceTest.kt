package pl.jclab.refio.core.services.longtask

import org.junit.jupiter.api.Test
import pl.jclab.refio.core.services.AgentPlanService
import pl.jclab.refio.core.services.AgentPlanStep
import pl.jclab.refio.core.services.PlanStepStatus
import pl.jclab.refio.core.services.PlanStore
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The main agent's plan is the backbone of a task that runs for hours. Kept only in memory, it was
 * gone after a restart of the IDE or the CLI, and the agent started planning from scratch.
 */
class AgentPlanPersistenceTest {

    private class MemoryStore : PlanStore {
        val rows = mutableMapOf<String, String?>()
        override fun savePlan(taskId: String, json: String?) {
            rows[taskId] = json
        }
        override fun loadPlan(taskId: String): String? = rows[taskId]
    }

    private val steps = listOf(
        AgentPlanStep(index = 0, title = "Parse the input", description = "src/parser.kt"),
        AgentPlanStep(index = 1, title = "Write tokenizer tests"),
    )

    @Test
    fun `plan survives a new plan service`() {
        val store = MemoryStore()
        AgentPlanService(store).apply {
            setPlan("t1", null, steps)
            updateStep("t1", null, 0, PlanStepStatus.COMPLETED, "done, 4 tests green")
        }

        val restored = AgentPlanService(store).getPlan("t1")

        assertEquals(listOf("Parse the input", "Write tokenizer tests"), restored?.map { it.title })
        assertEquals(listOf(PlanStepStatus.COMPLETED, PlanStepStatus.PENDING), restored?.map { it.status })
        assertEquals("done, 4 tests green", restored?.first()?.note)
        assertEquals("src/parser.kt", restored?.first()?.description)
    }

    @Test
    fun `restored plan reaches the prompt`() {
        val store = MemoryStore()
        AgentPlanService(store).setPlan("t1", null, steps)

        val section = AgentPlanService(store).buildPlanSection("t1")

        assertTrue(section.contains("Write tokenizer tests"), section)
    }

    @Test
    fun `a subagent's plan is not stored`() {
        val store = MemoryStore()
        AgentPlanService(store).setPlan("t1", "run-7", steps)

        assertNull(store.loadPlan("t1"), "a subagent's plan lives as long as its run")
    }
}
