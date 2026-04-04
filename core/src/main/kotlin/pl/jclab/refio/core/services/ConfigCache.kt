package pl.jclab.refio.core.services

import java.util.concurrent.ConcurrentHashMap

class ConfigCache(
    private val ttlMs: Long = 60_000L
) {
    private data class CachedEntry(
        val value: Any?,
        val expiresAt: Long
    )

    private val cache = ConcurrentHashMap<String, CachedEntry>()

    @Suppress("UNCHECKED_CAST")
    fun <T> getOrCompute(key: String, compute: () -> T): T {
        val now = System.currentTimeMillis()
        val existing = cache[key]
        if (existing != null && existing.expiresAt > now) {
            return existing.value as T
        }

        val value = compute()
        cache[key] = CachedEntry(value, now + ttlMs)
        return value
    }

    fun invalidate(key: String) {
        cache.remove(key)
    }

    fun invalidateByPrefix(prefix: String) {
        cache.keys.removeIf { it.startsWith(prefix) }
    }

    fun invalidateAll() {
        cache.clear()
    }
}
