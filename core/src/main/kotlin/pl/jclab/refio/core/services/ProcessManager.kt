package pl.jclab.refio.core.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.logging.dualLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = dualLogger("ProcessManager")

/**
 * Manages long-running background processes.
 * Processes are stored by process_id and can be monitored or stopped.
 *
 * Each process gets a daemon reader thread that continuously drains its merged
 * stdout/stderr into a bounded buffer, so a chatty child never blocks on a full
 * OS pipe, plus a [ProcessTreeTracker] that retains the handles of its children.
 * The tracker is what makes a self-backgrounding command (`server &`, `nohup …`)
 * stoppable: the shell exits at once and the real process is reparented away, so
 * without retained handles neither [stop] nor shutdown would reach it.
 *
 * "Running" therefore means the whole tree, not just the shell - an entry stays
 * addressable (and reapable) for as long as anything it started is alive.
 */
class ProcessManager(
    private val completedRetentionMs: Long = DEFAULT_COMPLETED_RETENTION_MS,
    cleanupIntervalMs: Long = DEFAULT_CLEANUP_INTERVAL_MS
) : AutoCloseable {

    class ManagedProcess(
        val processId: String,
        val command: String,
        val process: Process,
        val startedAt: Long = System.currentTimeMillis()
    ) {
        /** Retains handles of children that background themselves and reparent away. */
        internal val tracker = ProcessTreeTracker(process, threadName = "process-tree-$processId")

        /** Alive means the process OR anything it spawned - a backgrounded server included. */
        internal fun isTreeAlive(): Boolean = tracker.isTreeAlive()

        /** Lines drained from the process but not yet consumed via readOutput(). */
        internal val pendingLines = ArrayDeque<String>()
        internal var pendingChars = 0
        internal var droppedLines = 0

        internal fun append(line: String) {
            synchronized(pendingLines) {
                pendingLines.addLast(line)
                pendingChars += line.length
                // Bounded buffer: keep only the newest output if a chatty process
                // produces more than the cap between readOutput() calls.
                while (pendingChars > MAX_BUFFERED_CHARS && pendingLines.size > 1) {
                    val dropped = pendingLines.removeFirst()
                    pendingChars -= dropped.length
                    droppedLines++
                }
            }
        }

        internal fun take(maxLines: Int): List<String> {
            synchronized(pendingLines) {
                val result = mutableListOf<String>()
                if (droppedLines > 0) {
                    result.add("[output truncated: $droppedLines oldest lines dropped]")
                    droppedLines = 0
                }
                while (result.size < maxLines && pendingLines.isNotEmpty()) {
                    val line = pendingLines.removeFirst()
                    pendingChars -= line.length
                    result.add(line)
                }
                return result
            }
        }
    }

    private val processes = ConcurrentHashMap<String, ManagedProcess>()
    private val cleanupExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "process-manager-reaper").apply { isDaemon = true }
    }
    // No isAlive check: a command that backgrounded itself leaves an exited shell behind, and
    // skipping those is exactly how a server survived the host process and kept running.
    private val shutdownHook = Thread {
        for (managed in processes.values) {
            reap(managed)
        }
    }

    init {
        require(completedRetentionMs >= 0) { "completedRetentionMs must not be negative" }
        require(cleanupIntervalMs > 0) { "cleanupIntervalMs must be positive" }
        cleanupExecutor.scheduleWithFixedDelay(
            ::evictExpiredProcesses,
            cleanupIntervalMs,
            cleanupIntervalMs,
            TimeUnit.MILLISECONDS
        )
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    fun start(command: String, workingDir: java.io.File): ManagedProcess {
        val processId = UUID.randomUUID().toString().take(8)
        val pb = ProcessBuilder()
            .command(shellWrap(command))
            .directory(workingDir)
            .redirectErrorStream(true)
        val process = pb.start()
        val managed = ManagedProcess(processId, command, process)
        processes[processId] = managed
        startDrainThread(managed)
        logger.info { "Started background process $processId: $command" }
        return managed
    }

    fun get(processId: String): ManagedProcess? = processes[processId]

    fun stop(processId: String) {
        val managed = processes.remove(processId) ?: return
        reap(managed)
        logger.info { "Stopped process $processId" }
    }

    fun listRunning(): List<ManagedProcess> =
        processes.values.filter { it.isTreeAlive() }.toList()

    /**
     * Returns output accumulated since the last call (up to maxLines) and whether
     * the process is still running.
     */
    suspend fun readOutput(processId: String, maxLines: Int = 500): Pair<List<String>, Boolean> =
        withContext(Dispatchers.IO) {
            val managed = processes[processId]
                ?: return@withContext Pair(emptyList(), false)

            val lines = managed.take(maxLines)

            val isRunning = managed.isTreeAlive()
            if (!isRunning) {
                processes.remove(processId)
                managed.tracker.close()
            }

            Pair(lines, isRunning)
        }

    private fun startDrainThread(managed: ManagedProcess) {
        val drain = Thread {
            try {
                managed.process.inputStream.bufferedReader().forEachLine { line ->
                    managed.append(line)
                }
            } catch (e: Exception) {
                logger.debug { "Drain thread for ${managed.processId} ended: ${e.message}" }
            }
        }
        drain.isDaemon = true
        drain.name = "process-drain-${managed.processId}"
        drain.start()
    }

    /**
     * Drops finished entries after the retention window. An entry whose tree is still alive is
     * never dropped: losing it would leave a running server with no id to stop it by and outside
     * the shutdown hook's reach.
     */
    private fun evictExpiredProcesses() {
        val now = System.currentTimeMillis()
        processes.entries.removeIf { (_, managed) ->
            val expired = !managed.isTreeAlive() && now - managed.startedAt >= completedRetentionMs
            if (expired) {
                managed.tracker.close()
            }
            expired
        }
    }

    override fun close() {
        cleanupExecutor.shutdownNow()
        processes.values.forEach { managed -> reap(managed) }
        processes.clear()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }

    /** Kills the whole process tree and stops tracking it. */
    private fun reap(managed: ManagedProcess) {
        managed.tracker.destroyTree()
        managed.tracker.close()
    }

    private fun shellWrap(cmd: String): List<String> =
        if (System.getProperty("os.name").lowercase().contains("windows"))
            listOf("cmd.exe", "/c", cmd)
        else
            listOf("/bin/sh", "-c", cmd)

    companion object {
        /** Cap on buffered, unread output per process; oldest lines are dropped first. */
        const val MAX_BUFFERED_CHARS = 1_048_576
        const val DEFAULT_COMPLETED_RETENTION_MS = 5 * 60 * 1000L
        const val DEFAULT_CLEANUP_INTERVAL_MS = 30_000L
    }
}
