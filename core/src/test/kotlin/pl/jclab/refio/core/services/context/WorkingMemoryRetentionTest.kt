package pl.jclab.refio.core.services.context

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Working memory is process-lifetime state: nothing ever removed a task or a session from it, so a
 * long-lived IDE session accumulated one map per task until the project was closed. Entries per
 * task are already capped; the number of tracked tasks/sessions has to be capped as well.
 */
class WorkingMemoryRetentionTest {

    private fun entry(value: String) = WorkingMemoryEntry(iteration = 1, key = "facts", value = value)

    @Test
    fun `only the most recent tasks are kept in memory`() {
        val service = WorkingMemoryService()
        val overflow = WorkingMemoryService.MAX_TRACKED_SCOPES + 5

        repeat(overflow) { index ->
            service.recordEntries("task-$index", listOf(entry("fact for task $index")))
        }

        assertFalse(service.hasEntries("task-0"))
        assertTrue(service.hasEntries("task-${overflow - 1}"))
    }

    @Test
    fun `a task that is still being read is not evicted by newer tasks`() {
        val service = WorkingMemoryService()
        service.recordEntries("long-running", listOf(entry("still needed")))

        repeat(WorkingMemoryService.MAX_TRACKED_SCOPES) { index ->
            service.recordEntries("task-$index", listOf(entry("fact for task $index")))
            service.buildWorkingMemorySection("long-running", maxTokens = 200)
        }

        assertTrue(service.hasEntries("long-running"))
    }

    @Test
    fun `session scoped entries are capped as well`() {
        val service = WorkingMemoryService()
        val overflow = WorkingMemoryService.MAX_TRACKED_SCOPES + 5

        repeat(overflow) { index ->
            service.recordEntries("task-$index", listOf(entry("fact $index")), sessionId = "session-$index")
        }

        assertEquals("", service.buildSessionMemorySection("session-0", maxTokens = 200))
        assertTrue(service.buildSessionMemorySection("session-${overflow - 1}", maxTokens = 200).isNotEmpty())
    }
}
