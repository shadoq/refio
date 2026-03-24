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
 */
object FileLockManager {
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Execute [block] while holding a lock for the given file path.
     * Only one coroutine can hold the lock for a given path at a time.
     */
    suspend fun <T> withFileLock(path: String, block: suspend () -> T): T {
        val normalizedPath = java.nio.file.Path.of(path).toAbsolutePath().normalize().toString()
        val mutex = locks.getOrPut(normalizedPath) { Mutex() }
        return mutex.withLock { block() }
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
}
