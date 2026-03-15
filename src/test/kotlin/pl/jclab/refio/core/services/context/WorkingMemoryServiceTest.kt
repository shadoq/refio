package pl.jclab.refio.core.services.context

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkingMemoryServiceTest {

    @Test
    fun `recordEntries should evict least important entries over limit`() {
        val service = WorkingMemoryService(maxEntriesPerTask = 2)

        service.recordEntries(
            "task-1",
            listOf(
                WorkingMemoryEntry(iteration = 1, key = "facts", value = "low", importance = 1),
                WorkingMemoryEntry(iteration = 1, key = "facts", value = "high", importance = 10),
                WorkingMemoryEntry(iteration = 1, key = "facts", value = "medium", importance = 5)
            )
        )

        val section = service.buildWorkingMemorySection("task-1", maxTokens = 200)

        assertTrue(section.contains("high"))
        assertTrue(section.contains("medium"))
        assertFalse(section.contains("low"))
    }

    @Test
    fun `buildWorkingMemorySection should prefer recently accessed entries with same importance`() {
        val service = WorkingMemoryService(maxEntriesPerTask = 2)
        val older = Instant.parse("2024-01-01T00:00:00Z")
        val newer = Instant.parse("2024-01-02T00:00:00Z")

        service.recordEntries(
            "task-2",
            listOf(
                WorkingMemoryEntry(iteration = 1, key = "files", value = "older", importance = 5, timestamp = older, lastAccessedAt = older),
                WorkingMemoryEntry(iteration = 1, key = "files", value = "newer", importance = 5, timestamp = newer, lastAccessedAt = newer)
            )
        )

        val section = service.buildWorkingMemorySection("task-2", maxTokens = 40)

        assertTrue(section.contains("newer"))
    }
}
