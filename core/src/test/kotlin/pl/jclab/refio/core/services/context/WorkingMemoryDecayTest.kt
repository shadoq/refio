package pl.jclab.refio.core.services.context

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for working memory importance decay (Zmiana 2).
 */
class WorkingMemoryDecayTest {

    @Test
    fun `old entries with high base importance lose priority to newer entries`() {
        val service = WorkingMemoryService(maxEntriesPerTask = 50)
        val oldTime = Instant.parse("2024-01-01T00:00:00Z")

        // Old entry: importance 8, iteration 0
        service.recordEntries("task-1", listOf(
            WorkingMemoryEntry(
                iteration = 0, key = "files_read", value = "old_file.kt: 100 lines",
                importance = 8, timestamp = oldTime, lastAccessedAt = oldTime
            )
        ))

        // New entry: importance 5, iteration 25
        service.recordEntries("task-1", listOf(
            WorkingMemoryEntry(
                iteration = 25, key = "files_read", value = "new_file.kt: 50 lines",
                importance = 5, timestamp = Instant.now(), lastAccessedAt = Instant.now()
            )
        ))

        // At iteration 25: old entry effective = 8 - (25/5) = 8 - 5 = 3
        // New entry effective = 5 - (0/5) = 5
        // So new entry (5) should rank higher than old entry (3)
        val section = service.buildWorkingMemorySection("task-1", maxTokens = 50)

        // With very limited token budget, only one entry should fit
        // The newer entry should be picked because its effective importance is higher
        assertTrue(section.contains("new_file.kt"), "New entry should be prioritized over decayed old entry")
    }

    @Test
    fun `entries within 5 iterations do not decay`() {
        val service = WorkingMemoryService(maxEntriesPerTask = 50)

        service.recordEntries("task-1", listOf(
            WorkingMemoryEntry(iteration = 10, key = "files_read", value = "recent.kt: 30 lines", importance = 6),
            WorkingMemoryEntry(iteration = 13, key = "files_read", value = "newest.kt: 20 lines", importance = 6)
        ))

        // At max iteration 13: entry at 10 has gap of 3, decay = 3/5 = 0
        // Both should have effective importance 6
        val section = service.buildWorkingMemorySection("task-1", maxTokens = 500)
        assertTrue(section.contains("recent.kt"), "Recent entry within 5 iterations should not be decayed out")
        assertTrue(section.contains("newest.kt"), "Newest entry should be present")
    }

    @Test
    fun `effective importance floors at 1`() {
        val service = WorkingMemoryService(maxEntriesPerTask = 50)

        // Entry with importance 3 at iteration 0
        service.recordEntries("task-1", listOf(
            WorkingMemoryEntry(iteration = 0, key = "files_read", value = "ancient.kt", importance = 3)
        ))
        // Entry at iteration 100 — massive gap
        service.recordEntries("task-1", listOf(
            WorkingMemoryEntry(iteration = 100, key = "changes", value = "new_change.kt", importance = 2)
        ))

        // ancient.kt: effective = max(3 - 100/5, 1) = max(3 - 20, 1) = 1
        // new_change.kt: effective = max(2 - 0/5, 1) = 2
        val section = service.buildWorkingMemorySection("task-1", maxTokens = 500)

        // Both should still be present (50 max entries), just reordered
        assertTrue(section.contains("ancient.kt"), "Decayed entry should still be present (min importance 1)")
        assertTrue(section.contains("new_change.kt"))
    }

    @Test
    fun `trimEntries evicts decayed entries over limit`() {
        val service = WorkingMemoryService(maxEntriesPerTask = 2)

        // 3 entries: two old (importance 7 at iter 0) and one new (importance 5 at iter 30)
        service.recordEntries("task-1", listOf(
            WorkingMemoryEntry(iteration = 0, key = "files_read", value = "old_a.kt", importance = 7),
            WorkingMemoryEntry(iteration = 0, key = "files_read", value = "old_b.kt", importance = 7),
            WorkingMemoryEntry(iteration = 30, key = "changes", value = "new_change.kt", importance = 5)
        ))

        // maxEntries = 2, so one entry must be trimmed
        // At iter 30: old entries effective = 7 - 6 = 1, new entry effective = 5
        // new_change.kt (5) > old_a.kt (1) and old_b.kt (1)
        val section = service.buildWorkingMemorySection("task-1", maxTokens = 500)

        assertTrue(section.contains("new_change.kt"), "New entry should survive trim")
        // At least one old entry should be evicted
        val hasOldA = section.contains("old_a.kt")
        val hasOldB = section.contains("old_b.kt")
        // With maxEntries=2, at most 2 entries survive. New entry is one of them.
        // The other slot goes to one of the old entries (both have same effective importance=1)
        assertFalse(hasOldA && hasOldB, "At most one old entry should survive with maxEntries=2")
    }

    @Test
    fun `decay affects group ordering in section output`() {
        val service = WorkingMemoryService(maxEntriesPerTask = 50)

        // search_results group: entry at iter 0, importance 7
        service.recordEntries("task-1", listOf(
            WorkingMemoryEntry(iteration = 0, key = "search_results", value = "grep 'auth': found 5 matches", importance = 7)
        ))
        // changes group: entry at iter 20, importance 6
        service.recordEntries("task-1", listOf(
            WorkingMemoryEntry(iteration = 20, key = "changes", value = "Modified UserService.kt", importance = 6)
        ))

        // At iter 20: search_results effective = 7 - 4 = 3, changes effective = 6
        // changes group (6) should appear before search_results (3)
        val section = service.buildWorkingMemorySection("task-1", maxTokens = 500)

        val changesIdx = section.indexOf("## changes")
        val searchIdx = section.indexOf("## search_results")
        assertTrue(changesIdx >= 0 && searchIdx >= 0, "Both groups should be present")
        assertTrue(changesIdx < searchIdx, "changes group should appear before search_results due to decay")
    }
}
