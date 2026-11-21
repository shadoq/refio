package pl.jclab.refio.core.services.maintenance

import pl.jclab.refio.core.logging.dualLogger
import kotlin.math.abs

private val logger = dualLogger("TaskLease")

/** Where a process records the tasks it works on. */
interface LeaseStore {
    fun claimLease(taskId: String, pid: Long, startedAt: Long)
    fun releaseLease(taskId: String)
}

/** A process as the owner of a lease: its pid and its start time in epoch milliseconds (0 when unknown). */
data class ProcessOwner(val pid: Long, val startedAt: Long) {

    companion object {
        /** Start times read twice for one process may differ by rounding; a reused pid starts much later. */
        private const val START_TOLERANCE_MS = 1_000L

        fun current(): ProcessOwner {
            val self = ProcessHandle.current()
            return ProcessOwner(self.pid(), self.info().startInstant().map { it.toEpochMilli() }.orElse(0L))
        }

        /**
         * True when process [pid] runs and is the one that started at [startedAt], not a later process
         * given the same pid after a restart. An unknown start time counts as the same process, so a
         * task is never taken from a process that may still work on it.
         */
        fun isAlive(pid: Long, startedAt: Long): Boolean {
            val handle = ProcessHandle.of(pid).orElse(null) ?: return false
            if (!handle.isAlive) {
                return false
            }
            if (startedAt == 0L) {
                return true
            }
            return handle.info().startInstant()
                .map { abs(it.toEpochMilli() - startedAt) < START_TOLERANCE_MS }
                .orElse(true)
        }
    }
}

/**
 * Marks the tasks this process works on, so a later process can tell a dead owner's leftovers from
 * live work. Holds nest: a long task holds its task through all rounds and the checks between them,
 * each round's turn holds it again, and the mark is cleared when the outermost hold ends. A failed
 * write is logged and ignored; the mark only helps the repair after a crash.
 */
class TaskLease(private val store: LeaseStore, private val owner: ProcessOwner) {

    private val holds = HashMap<String, Int>()

    fun acquire(taskId: String) {
        synchronized(holds) {
            val count = (holds[taskId] ?: 0) + 1
            holds[taskId] = count
            if (count == 1) {
                runCatching { store.claimLease(taskId, owner.pid, owner.startedAt) }
                    .onFailure { logger.warn { "[LEASE] could not mark task $taskId as running here: ${it.message}" } }
            }
        }
    }

    fun release(taskId: String) {
        synchronized(holds) {
            val count = holds[taskId] ?: return
            if (count > 1) {
                holds[taskId] = count - 1
                return
            }
            holds.remove(taskId)
            runCatching { store.releaseLease(taskId) }
                .onFailure { logger.warn { "[LEASE] could not clear the running mark of task $taskId: ${it.message}" } }
        }
    }

    suspend fun <T> holding(taskId: String, block: suspend () -> T): T {
        acquire(taskId)
        try {
            return block()
        } finally {
            release(taskId)
        }
    }
}
