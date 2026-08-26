package pl.jclab.refio.core.services

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Keeps a live snapshot of a process's descendants so they can still be killed after they outlive it.
 *
 * A command that backgrounds itself (`server &`, `nohup …`, any daemonizing binary) makes the shell
 * exit immediately while the grandchild is reparented to init. From that moment the grandchild is
 * gone from `Process.descendants()`, so a kill of the (already dead) shell reaches nothing: the
 * server keeps running and keeps the inherited stdout pipe open. Retaining the handles while the
 * shell is still alive is the only way to reach it afterwards.
 *
 * A grandchild that is spawned *and* reparented entirely between two polls cannot be seen by any
 * handle-based approach; the poll interval is short for that reason.
 */
class ProcessTreeTracker(
    private val process: Process,
    pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    threadName: String = "process-tree-tracker",
) : AutoCloseable {

    private val descendants = ConcurrentHashMap.newKeySet<ProcessHandle>()
    private val polling = AtomicBoolean(true)

    private val poller = thread(isDaemon = true, name = threadName) {
        while (polling.get() && process.isAlive) {
            snapshot()
            try {
                Thread.sleep(pollIntervalMs)
            } catch (interrupted: InterruptedException) {
                break
            }
        }
        snapshot()
    }

    /** True while the root process or any descendant seen so far is still running. */
    fun isTreeAlive(): Boolean = process.isAlive || descendants.any { it.isAlive }

    /** Forcibly kills the root process and every descendant ever seen, reparented ones included. */
    fun destroyTree() {
        snapshot()
        descendants.forEach { handle -> runCatching { handle.destroyForcibly() } }
        runCatching { process.descendants().forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }

    /** Stops polling. Kills nothing - call [destroyTree] for that. */
    override fun close() {
        if (polling.compareAndSet(true, false)) {
            poller.interrupt()
        }
    }

    private fun snapshot() {
        runCatching { process.descendants().forEach { descendants.add(it) } }
    }

    companion object {
        /** How often the descendant tree is snapshotted while the root process is alive. */
        const val DEFAULT_POLL_INTERVAL_MS = 50L
    }
}
