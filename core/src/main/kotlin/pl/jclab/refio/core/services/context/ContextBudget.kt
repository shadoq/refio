package pl.jclab.refio.core.services.context

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Cached stable context layer — invalidated only on project file change.
 */
data class StableContextCache(
    val content: String,
    val tokensUsed: Int,
    val contextVersion: Long,
    val cachedAt: Long = System.currentTimeMillis()
)

/**
 * Manages context layer caching across turns.
 * - STABLE layer: cached, invalidated on project file change (not per-turn)
 * - Tool descriptions: cached, invalidated on permission change
 */
class ContextLayerCache {
    private val stableCache = ConcurrentHashMap<String, StableContextCache>()
    private val toolDescriptionsCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val contextVersionCounter = AtomicLong(0)

    fun getStableContext(taskId: String): StableContextCache? = stableCache[taskId]

    fun putStableContext(taskId: String, content: String, tokensUsed: Int) {
        stableCache[taskId] = StableContextCache(
            content = content,
            tokensUsed = tokensUsed,
            contextVersion = contextVersionCounter.get()
        )
    }

    fun getToolDescriptions(taskId: String): String? {
        val (desc, version) = toolDescriptionsCache[taskId] ?: return null
        return if (version == contextVersionCounter.get()) desc else null
    }

    fun putToolDescriptions(taskId: String, descriptions: String) {
        toolDescriptionsCache[taskId] = descriptions to contextVersionCounter.get()
    }

    fun invalidateStable(taskId: String) {
        stableCache.remove(taskId)
        contextVersionCounter.incrementAndGet()
    }

    fun invalidateAll() {
        stableCache.clear()
        toolDescriptionsCache.clear()
        contextVersionCounter.incrementAndGet()
    }

    fun currentContextVersion(): Long = contextVersionCounter.get()

    fun getContextStabilityPercent(taskId: String): Int {
        val cached = stableCache[taskId] ?: return 0
        return if (cached.contextVersion == contextVersionCounter.get()) 100 else 0
    }
}

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
            ContextSection.RAG_FRAGMENTS to 2000,
            ContextSection.CONVERSATION to 8000,
            ContextSection.REFERENCE to 2500
        )
        private val baselineTotalBudget = defaultBudgets.values.sum()

        fun forContextSize(
            contextSize: Int,
            inputRatio: Double = 0.85,
            overrides: Map<ContextSection, Int> = emptyMap(),
            totalTokensOverride: Int? = null
        ): ContextBudget {
            val available = totalTokensOverride ?: max(1, (contextSize * inputRatio).toInt())
            val base = scaleBudgetsForAvailableTokens(available)
            val merged = base.toMutableMap().apply { putAll(overrides) }
            return ContextBudget(
                totalTokens = available,
                sectionBudgets = normalizeBudgets(available, merged)
            )
        }

        private fun scaleBudgetsForAvailableTokens(availableTokens: Int): Map<ContextSection, Int> {
            if (availableTokens <= baselineTotalBudget) return defaultBudgets

            val scale = availableTokens.toDouble() / baselineTotalBudget.toDouble()
            return defaultBudgets.mapValues { (_, value) ->
                max(1, (value * scale).roundToInt())
            }
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
