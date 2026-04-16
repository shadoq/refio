package pl.jclab.refio.cli.tui.state

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests subtask state management in TuiState (pure state tests).
 * ViewModel methods that call router are tested via integration tests.
 *
 * Uses core [pl.jclab.refio.core.api.SubtaskResponse] directly — TUI no longer has
 * its own subtask/plan DTOs (DTO de-duplication, 2026-04-16).
 */
class TuiViewModelSubtaskTest {

    @Test
    fun `SubtaskResponse copy should update status`() {
        val original = subtaskFixture(id = "s1", description = "test", status = "NEW")
        val updated = original.copy(status = "RUNNING")
        assertEquals("NEW", original.status)
        assertEquals("RUNNING", updated.status)
    }

    @Test
    fun `SubtaskResponse copy should update errorMessage`() {
        val original = subtaskFixture(id = "s1", description = "test", status = "RUNNING")
        val failed = original.copy(status = "FAILED", errorMessage = "Connection timeout")
        assertEquals("FAILED", failed.status)
        assertEquals("Connection timeout", failed.errorMessage)
    }

    @Test
    fun `TuiPlanApproval should carry plan steps`() {
        val steps = listOf(
            subtaskFixture(id = "s1", description = "read", kind = "read_file"),
            subtaskFixture(id = "s2", description = "edit", kind = "code_editing"),
        )
        val approval = TuiPlanApproval(taskId = "t1", steps = steps)
        assertEquals(2, approval.steps.size)
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
    fun `subtask list counts should work correctly`() {
        val subtasks = listOf(
            subtaskFixture(id = "s1", description = "a", status = "COMPLETED"),
            subtaskFixture(id = "s2", description = "b", status = "RUNNING"),
            subtaskFixture(id = "s3", description = "c", status = "NEW"),
        )
        val completed = subtasks.count { it.status == "COMPLETED" }
        val running = subtasks.count { it.status == "RUNNING" }
        assertEquals(1, completed)
        assertEquals(1, running)
    }

    @Test
    fun `subtask status update should be immutable`() {
        val list = listOf(
            subtaskFixture(id = "s1", description = "test", status = "NEW"),
        )
        val updated = list.map {
            if (it.id == "s1") it.copy(status = "APPROVED") else it
        }
        assertEquals("NEW", list[0].status)
        assertEquals("APPROVED", updated[0].status)
    }
}
