package pl.jclab.refio.core.services.context

import kotlin.math.max

/**
 * Token budget configuration for context building.
 */
data class ContextBudget(
    val totalTokens: Int,
    val sectionBudgets: Map<ContextSection, Int>
) {
    fun budgetFor(section: ContextSection): Int = sectionBudgets[section] ?: 0

    fun withOverrides(overrides: Map<ContextSection, Int>, totalTokensOverride: Int? = null): ContextBudget {
        val merged = sectionBudgets.toMutableMap().apply { putAll(overrides) }
        val total = totalTokensOverride ?: totalTokens
        return ContextBudget(
            totalTokens = total,
            sectionBudgets = normalizeBudgets(total, merged)
        )
    }

    fun redistributeUnused(actualUsage: Map<ContextSection, Int>): ContextBudget {
        val unused = sectionBudgets.entries.sumOf { (section, budget) ->
            max(0, budget - (actualUsage[section] ?: 0))
        }

        if (unused <= 0) return this

        val priorities = listOf(
            ContextSection.CONVERSATION,
            ContextSection.USER_CONTEXT,
            ContextSection.RAG_FRAGMENTS,
            ContextSection.WORKING_MEMORY
        )

        val redistributed = sectionBudgets.toMutableMap()
        var remaining = unused

        for (section in priorities) {
            val currentBudget = redistributed[section] ?: continue
            if (remaining <= 0) break
            val bonus = minOf(remaining, max(1, currentBudget / 2))
            redistributed[section] = currentBudget + bonus
            remaining -= bonus
        }

        return ContextBudget(totalTokens = totalTokens, sectionBudgets = redistributed)
    }

    companion object {
        private val defaultBudgets = mapOf(
            ContextSection.SYSTEM_PROMPT to 3000,
            ContextSection.TOOL_DESCRIPTIONS to 2000,
            ContextSection.WORKING_MEMORY to 3000,
            ContextSection.PROJECT_CONTEXT to 1500,
            ContextSection.RECENT_WORK to 8000,
            ContextSection.USER_CONTEXT to 5000,
            ContextSection.RAG_FRAGMENTS to 3000,
            ContextSection.CONVERSATION to 4000,
            ContextSection.REFERENCE to 2500
        )

        fun forContextSize(
            contextSize: Int,
            inputRatio: Double = 0.85,
            overrides: Map<ContextSection, Int> = emptyMap(),
            totalTokensOverride: Int? = null
        ): ContextBudget {
            val available = totalTokensOverride ?: max(1, (contextSize * inputRatio).toInt())
            val base = defaultBudgets
            val merged = base.toMutableMap().apply { putAll(overrides) }
            return ContextBudget(
                totalTokens = available,
                sectionBudgets = normalizeBudgets(available, merged)
            )
        }

        private fun normalizeBudgets(
            totalTokens: Int,
            budgets: Map<ContextSection, Int>
        ): Map<ContextSection, Int> {
            val sum = budgets.values.sum().coerceAtLeast(1)
            if (sum <= totalTokens) return budgets

            val scale = totalTokens.toDouble() / sum
            return budgets.mapValues { (_, value) ->
                max(1, (value * scale).toInt())
            }
        }
    }
}
