package pl.jclab.refio.cli.tui.state

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests subtask state management in TuiState (pure state tests).
 * ViewModel methods that call router are tested via integration tests.
 */
class TuiViewModelSubtaskTest {

    @Test
    fun `TuiSubtask should have correct default values`() {
        val subtask = TuiSubtask(id = "s1", name = "test step")
        assertEquals("NEW", subtask.status)
        assertEquals("", subtask.description)
        assertNull(subtask.toolName)
        assertNull(subtask.error)
        assertEquals(0L, subtask.tokensIn)
        assertEquals(0L, subtask.tokensOut)
        assertEquals(0.0, subtask.costUsd)
    }

    @Test
    fun `TuiSubtask copy should update status`() {
        val original = TuiSubtask(id = "s1", name = "test", status = "NEW")
        val updated = original.copy(status = "RUNNING")
        assertEquals("NEW", original.status)
        assertEquals("RUNNING", updated.status)
    }

    @Test
    fun `TuiSubtask copy should update error`() {
        val original = TuiSubtask(id = "s1", name = "test", status = "RUNNING")
        val failed = original.copy(status = "FAILED", error = "Connection timeout")
        assertEquals("FAILED", failed.status)
        assertEquals("Connection timeout", failed.error)
    }

    @Test
    fun `TuiPlan should track read and write steps`() {
        val plan = TuiPlan(
            taskId = "t1",
            steps = listOf(
                TuiSubtask(id = "s1", name = "read", toolName = "read_file"),
                TuiSubtask(id = "s2", name = "edit", toolName = "code_editing")
            ),
            totalReadSteps = 1,
            totalWriteSteps = 1
        )
        assertEquals(2, plan.steps.size)
        assertEquals(1, plan.totalReadSteps)
        assertEquals(1, plan.totalWriteSteps)
    }

    @Test
    fun `TuiPlanApproval should be visible by default`() {
        val plan = TuiPlan(taskId = "t1", steps = emptyList())
        val approval = TuiPlanApproval(taskId = "t1", plan = plan)
        assertTrue(approval.isVisible)
    }

    @Test
    fun `TuiState selectedStepIndex should default to 0`() {
        val state = TuiState()
        assertEquals(0, state.selectedStepIndex)
    }

    @Test
    fun `TuiState isPaused should default to false`() {
        val state = TuiState()
        assertFalse(state.isPaused)
    }

    @Test
    fun `subtask list operations should work correctly`() {
        val subtasks = listOf(
            TuiSubtask(id = "s1", name = "a", status = "COMPLETED"),
            TuiSubtask(id = "s2", name = "b", status = "RUNNING"),
            TuiSubtask(id = "s3", name = "c", status = "NEW")
        )
        val completed = subtasks.count { it.status == "COMPLETED" }
        val running = subtasks.count { it.status == "RUNNING" }
        assertEquals(1, completed)
        assertEquals(1, running)
    }

    @Test
    fun `subtask status update should be immutable`() {
        val list = listOf(
            TuiSubtask(id = "s1", name = "test", status = "NEW")
        )
        val updated = list.map {
            if (it.id == "s1") it.copy(status = "APPROVED") else it
        }
        assertEquals("NEW", list[0].status)
        assertEquals("APPROVED", updated[0].status)
    }
}
