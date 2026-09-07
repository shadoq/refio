package pl.jclab.refio.core.tools

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Prevents concurrent writes to the same file by multiple agents.
 * Keyed by normalized absolute path.
 *
 * Used by write tools (CodeEditingTool, CreateNewFileTool, AdvanceCodeEditingTool,
 * MultiEditTool, MultiLineEditorTool) to ensure atomic file operations
 * when multiple agents run in parallel.
 *
 * Locks are evicted after [EVICTION_THRESHOLD] entries to prevent unbounded memory growth.
 * Eviction only removes entries that are both unlocked AND older than [EVICTION_AGE_MS]; a caller
 * that acquires an entry which eviction removed meanwhile retries with the current one.
 */
object FileLockManager {
    private const val EVICTION_THRESHOLD = 500
    private const val EVICTION_AGE_MS = 60_000L // Only evict locks unused for >60s

    private class LockEntry(
        val mutex: Mutex = Mutex(),
        @Volatile var lastUsed: Long = System.currentTimeMillis()
    )

    private val locks = ConcurrentHashMap<String, LockEntry>()

    /**
     * Execute [block] while holding a lock for the given file path.
     * Only one coroutine can hold the lock for a given path at a time.
     */
    suspend fun <T> withFileLock(path: String, block: suspend () -> T): T {
        val normalizedPath = java.nio.file.Path.of(path).toAbsolutePath().normalize().toString()
        while (true) {
            val entry = locks.computeIfAbsent(normalizedPath) { LockEntry() }

            // Looking the entry up and acquiring it are two steps. If eviction drops the entry in
            // between, the next caller creates a second mutex for this path and both callers think
            // they own the file. So after acquiring, check that the map still points at the entry we
            // locked; if it does not, this lock guards nothing and we start over.
            val holder = entry.mutex.withLock {
                if (locks[normalizedPath] !== entry) {
                    null
                } else {
                    entry.lastUsed = System.currentTimeMillis()

                    // Evict stale entries after acquiring our lock (best-effort cleanup)
                    if (locks.size > EVICTION_THRESHOLD) {
                        evictStaleLocks()
                    }

                    ResultHolder(block())
                }
            }
            if (holder != null) {
                return holder.value
            }
        }
    }

    /** [block] may legitimately return null, so success is carried, not signalled by nullability. */
    private class ResultHolder<T>(val value: T)

    /**
     * Remove locks that are both unlocked AND haven't been used for [EVICTION_AGE_MS].
     * A caller that had already looked this entry up is covered by the revalidation in
     * [withFileLock], not by these conditions.
     */
    private fun evictStaleLocks() {
        val now = System.currentTimeMillis()
        val iterator = locks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val age = now - entry.value.lastUsed
            // Only evict if BOTH: not currently locked AND old enough
            if (age > EVICTION_AGE_MS && !entry.value.mutex.isLocked) {
                iterator.remove()
            }
        }
    }

    /**
     * Get the number of active locks (for monitoring/testing).
     */
    fun activeLockCount(): Int = locks.size

    /**
     * Clear all locks (for testing only).
     */
    fun clear() {
        locks.clear()
    }

    /** Test only: drop every entry whose mutex is currently unlocked, ignoring age. */
    internal fun evictAllUnlockedForTest() {
        locks.entries.removeIf { !it.value.mutex.isLocked }
    }
}
