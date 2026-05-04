package pl.jclab.refio.core.llm

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService
import java.util.concurrent.ConcurrentHashMap

private val logger = dualLogger("NativeToolsFallbackTracker")

/**
 * Registry of models that failed native function-calling and should be routed
 * through the JSON-envelope contract instead.
 *
 * Persistence: when [bind] is called with a [ConfigService], the in-memory set
 * is hydrated from [ConfigKeys.MODELS_NATIVE_TOOLS_FALLBACKS] (comma-separated
 * model IDs) and every subsequent [markFallback] mirrors back to disk. Without
 * a binding the tracker still works as a process-local cache.
 *
 * Reads ([isFallback], [getFallbackSet]) stay in-memory for fast-path access on
 * every turn; writes are guarded by the single-writer mutation block.
 */
object NativeToolsFallbackTracker {
    private val fallbackModels = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var configService: ConfigService? = null

    /**
     * Wire the tracker to a [ConfigService] for persistence. Idempotent — calling
     * with the same service is a no-op; calling with a different service rebinds
     * and rehydrates the in-memory set.
     */
    fun bind(configService: ConfigService) {
        if (this.configService === configService) return
        this.configService = configService
        val persisted = readPersisted(configService)
        // Merge instead of replace so any in-memory entries already accumulated in
        // this run survive (e.g. tracker was used before bind() — defensive only,
        // expected to be empty in practice).
        if (persisted.isNotEmpty()) {
            val added = fallbackModels.addAll(persisted)
            if (added) {
                logger.info { "[NATIVE_TOOLS_FALLBACK] Loaded ${persisted.size} persisted model(s): $persisted" }
            }
        }
    }

    fun isFallback(modelId: String): Boolean = modelId in fallbackModels

    fun markFallback(modelId: String, reason: String) {
        if (fallbackModels.add(modelId)) {
            logger.warn { "[NATIVE_TOOLS_FALLBACK] Marking $modelId as fallback: $reason" }
            persist()
        }
    }

    fun getFallbackSet(): Set<String> = fallbackModels.toSet()

    /**
     * Drop all entries (in-memory and persisted). Intended for user action
     * ("retry native tools for all models") and tests.
     */
    fun clear() {
        if (fallbackModels.isEmpty()) return
        fallbackModels.clear()
        persist()
        logger.info { "[NATIVE_TOOLS_FALLBACK] Cleared all entries" }
    }

    /**
     * Drop a single entry. Returns true if the entry existed.
     */
    fun unmark(modelId: String): Boolean {
        if (!fallbackModels.remove(modelId)) return false
        logger.info { "[NATIVE_TOOLS_FALLBACK] Unmarked $modelId" }
        persist()
        return true
    }

    private fun persist() {
        val cfg = configService ?: return
        val joined = fallbackModels.toSortedSet().joinToString(",")
        try {
            cfg.setTyped(ConfigKeys.MODELS_NATIVE_TOOLS_FALLBACKS, joined)
        } catch (e: Exception) {
            logger.warn(e) { "[NATIVE_TOOLS_FALLBACK] Failed to persist fallback set; staying in-memory only" }
        }
    }

    private fun readPersisted(cfg: ConfigService): Set<String> {
        val raw = try {
            cfg.getTyped(ConfigKeys.MODELS_NATIVE_TOOLS_FALLBACKS)
        } catch (e: Exception) {
            logger.warn(e) { "[NATIVE_TOOLS_FALLBACK] Failed to read persisted set; starting empty" }
            return emptySet()
        }
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
