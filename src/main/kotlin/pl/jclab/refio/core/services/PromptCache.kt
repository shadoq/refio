package pl.jclab.refio.core.services

import pl.jclab.refio.core.db.PromptType
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.services.logging.dualLogger
import java.util.concurrent.ConcurrentHashMap

private val logger = dualLogger("PromptCache")

/**
 * Cache for static prompt components to enable LLM prompt caching.
 *
 * Strategy based on research:
 * - Static content (system prompt, tool descriptions) at the beginning
 * - Dynamic content (conversation, tool results) at the end
 * - Cache invalidation on config changes
 * - TTL matching Anthropic cache lifetime (5 minutes)
 *
 * Reference: ADR-0028 - Prompt Caching
 */
class PromptCache(
    private val toolDescriptionBuilder: ToolDescriptionBuilder,
    private val promptsService: PromptsService
) {
    // Cache entries per (mode, taskId) combination
    private data class CacheEntry(
        val systemPrompt: String,
        val toolDescriptions: String,
        val cacheKey: String,
        val createdAt: Long,
        val tokenEstimate: Int
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    // Cache statistics
    private var hitCount = 0
    private var missCount = 0

    // Cache TTL (5 minutes, matching Anthropic cache lifetime)
    private val cacheTtlMs = 5 * 60 * 1000L

    /**
     * Get cached static prefix or build and cache it.
     *
     * @param mode Task mode (PLAN/AGENT)
     * @param taskId Task ID
     * @param tokenEstimator For estimating cache entry size
     * @return Cached or newly built static prefix
     */
    fun getOrBuildStaticPrefix(
        mode: TaskMode,
        taskId: String,
        tokenEstimator: TokenEstimator
    ): StaticPromptPrefix {
        val cacheKey = buildCacheKey(mode, taskId)
        val existing = cache[cacheKey]

        if (existing != null && !isExpired(existing)) {
            hitCount++
            logger.debug { "[CACHE_HIT] Using cached prefix for $mode/$taskId" }
            return StaticPromptPrefix(
                systemPrompt = existing.systemPrompt,
                toolDescriptions = existing.toolDescriptions,
                fromCache = true,
                tokenEstimate = existing.tokenEstimate
            )
        }

        missCount++

        // Build new prefix
        val systemPrompt = buildSystemPrompt(mode)
        val toolDescriptions = toolDescriptionBuilder.getToolDescriptions(mode, taskId)

        val fullPrefix = "$systemPrompt\n\n$toolDescriptions"
        val tokenEstimate = tokenEstimator.estimateString(fullPrefix)

        // Cache it
        val entry = CacheEntry(
            systemPrompt = systemPrompt,
            toolDescriptions = toolDescriptions,
            cacheKey = cacheKey,
            createdAt = System.currentTimeMillis(),
            tokenEstimate = tokenEstimate
        )
        cache[cacheKey] = entry

        logger.info {
            "[CACHE_MISS] Built and cached prefix for $mode/$taskId " +
            "(~$tokenEstimate tokens, hits=$hitCount, misses=$missCount)"
        }

        return StaticPromptPrefix(
            systemPrompt = systemPrompt,
            toolDescriptions = toolDescriptions,
            fromCache = false,
            tokenEstimate = tokenEstimate
        )
    }

    /**
     * Invalidate cache for a task (call on config change).
     *
     * @param taskId Task ID
     */
    fun invalidate(taskId: String) {
        val keysToRemove = cache.keys.filter { it.endsWith(":$taskId") }
        keysToRemove.forEach { cache.remove(it) }
        logger.debug { "[CACHE_INVALIDATE] Removed ${keysToRemove.size} entries for $taskId" }
    }

    /**
     * Invalidate all cache entries (e.g., on prompts change).
     */
    fun invalidateAll() {
        val size = cache.size
        cache.clear()
        logger.info { "[CACHE_CLEAR] Cleared all $size entries" }
    }

    /**
     * Get cache hit rate.
     *
     * @return Hit rate between 0.0 and 1.0
     */
    fun getHitRate(): Double {
        val total = hitCount + missCount
        return if (total > 0) hitCount.toDouble() / total else 0.0
    }

    /**
     * Get cache statistics.
     */
    fun getStats(): CacheStats = CacheStats(
        hitCount = hitCount,
        missCount = missCount,
        hitRate = getHitRate(),
        size = cache.size
    )

    /**
     * Clear all cache entries.
     */
    fun clear() {
        cache.clear()
        hitCount = 0
        missCount = 0
        logger.info { "[CACHE_CLEAR] All entries and stats cleared" }
    }

    private fun buildCacheKey(mode: TaskMode, taskId: String): String {
        // Include config hash to invalidate on changes
        val configHash = promptsService.hashCode()
        return "${mode.name}:$configHash:$taskId"
    }

    private fun isExpired(entry: CacheEntry): Boolean {
        return System.currentTimeMillis() - entry.createdAt > cacheTtlMs
    }

    private fun buildSystemPrompt(mode: TaskMode): String {
        val type = when (mode) {
            TaskMode.PLAN -> PromptType.SYSTEM_PLAN
            TaskMode.AGENT -> PromptType.SYSTEM_AGENT
            else -> throw IllegalArgumentException("Invalid mode for cache: $mode")
        }
        return promptsService.getSystemPrompt(type)
    }
}

/**
 * Static prompt prefix data.
 *
 * @property systemPrompt System prompt content
 * @property toolDescriptions Tool descriptions string
 * @property fromCache Whether this came from cache
 * @property tokenEstimate Estimated token count
 */
data class StaticPromptPrefix(
    val systemPrompt: String,
    val toolDescriptions: String,
    val fromCache: Boolean,
    val tokenEstimate: Int
)

/**
 * Cache statistics.
 *
 * @property hitCount Number of cache hits
 * @property missCount Number of cache misses
 * @property hitRate Cache hit rate (0.0-1.0)
 * @property size Current cache size
 */
data class CacheStats(
    val hitCount: Int,
    val missCount: Int,
    val hitRate: Double,
    val size: Int
)
