package pl.jclab.refio.core.services.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextBudgetTest {

    @Test
    fun `redistributeUnused should increase priority sections when budget is left unused`() {
        val budget = ContextBudget(
            totalTokens = 100,
            sectionBudgets = mapOf(
                ContextSection.PROJECT_CONTEXT to 30,
                ContextSection.USER_CONTEXT to 20,
                ContextSection.RAG_FRAGMENTS to 20,
                ContextSection.CONVERSATION to 10,
                ContextSection.WORKING_MEMORY to 10
            )
        )

        val redistributed = budget.redistributeUnused(
            actualUsage = mapOf(
                ContextSection.PROJECT_CONTEXT to 10,
                ContextSection.USER_CONTEXT to 5
            )
        )

        assertTrue(redistributed.budgetFor(ContextSection.CONVERSATION) > budget.budgetFor(ContextSection.CONVERSATION))
        assertTrue(redistributed.budgetFor(ContextSection.USER_CONTEXT) > budget.budgetFor(ContextSection.USER_CONTEXT))
        assertEquals(30, redistributed.budgetFor(ContextSection.PROJECT_CONTEXT))
    }

    @Test
    fun `redistributeUnused should keep original budget when nothing is unused`() {
        val budget = ContextBudget(
            totalTokens = 40,
            sectionBudgets = mapOf(
                ContextSection.PROJECT_CONTEXT to 20,
                ContextSection.CONVERSATION to 20
            )
        )

        val redistributed = budget.redistributeUnused(
            actualUsage = mapOf(
                ContextSection.PROJECT_CONTEXT to 20,
                ContextSection.CONVERSATION to 20
            )
        )

        assertEquals(budget, redistributed)
    }

    @Test
    fun `redistributeUnused - Bug 2C regression - RECENT_WORK gets budget slack first`() {
        // Bug 2C: the old priority list did not include RECENT_WORK so it never
        // received budget slack from under-used stable sections. In practice this
        // meant that on a 128k context the agent only saw ~22k of prior work even
        // though 80k were unused. RECENT_WORK must now be the FIRST section to grow.
        val budget = ContextBudget(
            totalTokens = 1_000,
            sectionBudgets = mapOf(
                ContextSection.PROJECT_CONTEXT to 500,
                ContextSection.RECENT_WORK to 100,
                ContextSection.WORKING_MEMORY to 100,
                ContextSection.CONVERSATION to 100,
                ContextSection.USER_CONTEXT to 100,
                ContextSection.RAG_FRAGMENTS to 100
            )
        )

        // PROJECT_CONTEXT only used 100 of 500 → 400 unused tokens to redistribute.
        val redistributed = budget.redistributeUnused(
            actualUsage = mapOf(ContextSection.PROJECT_CONTEXT to 100)
        )

        val originalRecentWork = budget.budgetFor(ContextSection.RECENT_WORK)
        val newRecentWork = redistributed.budgetFor(ContextSection.RECENT_WORK)
        assertTrue(
            newRecentWork > originalRecentWork,
            "RECENT_WORK must grow after redistribution (was $originalRecentWork, now $newRecentWork)"
        )

        val originalWorkingMem = budget.budgetFor(ContextSection.WORKING_MEMORY)
        val newWorkingMem = redistributed.budgetFor(ContextSection.WORKING_MEMORY)
        assertTrue(
            newWorkingMem > originalWorkingMem,
            "WORKING_MEMORY must also grow (was $originalWorkingMem, now $newWorkingMem)"
        )

        // RECENT_WORK comes BEFORE WORKING_MEMORY in the priority list, so it must
        // get at least as much bonus as WORKING_MEMORY.
        val recentBonus = newRecentWork - originalRecentWork
        val wmBonus = newWorkingMem - originalWorkingMem
        assertTrue(
            recentBonus >= wmBonus,
            "RECENT_WORK must get first pick of unused budget (got $recentBonus vs WM $wmBonus)"
        )
    }
}
