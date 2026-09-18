package pl.jclab.refio.core.debug

import java.util.concurrent.ConcurrentHashMap

/**
 * One path a turn wrote to: how many writing calls touched it and whether it actually ended up
 * different from how it started.
 *
 * [netChanged] is the point of this type. A run that edited the wrong file and then restored it
 * leaves the tree byte-identical to the fixture, so every end-of-run comparison calls it clean; the
 * only trace that it happened is that the path was written twice and came back to its original
 * content.
 */
data class FileWriteRecord(
    val path: String,
    /** How many writing calls touched this path. */
    val writes: Int,
    /** Whether the file differs from what it was when the turn first touched it. */
    val netChanged: Boolean,
)

/**
 * Per-task record of the files a turn wrote, on its way to `run.json.metrics.filesWritten`.
 * Thread-safe process-global singleton keyed by `taskId`, mirroring [TurnVerificationTracker].
 */
object TurnFileWriteTracker {

    /** Content hashes of one path: what it was when first touched, what it is now. */
    private data class PathState(val writes: Int, val originalHash: String?, val latestHash: String?)

    private val writes = ConcurrentHashMap<String, MutableMap<String, PathState>>()

    /**
     * Note that a writing call touched [path].
     *
     * @param hashBefore content hash read just before the call; null when the file did not exist.
     *        Kept only from the FIRST call for this path, so "how it started" means the start of
     *        the turn and not the start of the latest edit.
     * @param hashAfter content hash read just after the call; null when the file does not exist.
     */
    fun recordWrite(taskId: String, path: String, hashBefore: String?, hashAfter: String?) {
        val perTask = writes.computeIfAbsent(taskId) { ConcurrentHashMap() }
        perTask.compute(path) { _, prev ->
            if (prev == null) {
                PathState(writes = 1, originalHash = hashBefore, latestHash = hashAfter)
            } else {
                prev.copy(writes = prev.writes + 1, latestHash = hashAfter)
            }
        }
    }

    /** The files [taskId] wrote, in path order so two runs of the same task compare cleanly. */
    fun recordsFor(taskId: String): List<FileWriteRecord> =
        writes[taskId].orEmpty().entries
            .sortedBy { it.key }
            .map { (path, state) ->
                FileWriteRecord(
                    path = path,
                    writes = state.writes,
                    netChanged = state.originalHash != state.latestHash,
                )
            }

    /** Test-only: forget all recorded writes. */
    fun reset() {
        writes.clear()
    }
}
