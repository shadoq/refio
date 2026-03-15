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
}
