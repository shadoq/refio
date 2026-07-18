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
 * OS pipe. A JVM shutdown hook kills any still-running children so they are not
 * orphaned when the host process exits.
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
    private val shutdownHook = Thread {
        for (managed in processes.values) {
            if (managed.process.isAlive) destroyTree(managed.process)
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
        destroyTree(managed.process)
        logger.info { "Stopped process $processId" }
    }

    fun listRunning(): List<ManagedProcess> =
        processes.values.filter { it.process.isAlive }.toList()

    /**
     * Returns output accumulated since the last call (up to maxLines) and whether
     * the process is still running.
     */
    suspend fun readOutput(processId: String, maxLines: Int = 500): Pair<List<String>, Boolean> =
        withContext(Dispatchers.IO) {
            val managed = processes[processId]
                ?: return@withContext Pair(emptyList(), false)

            val lines = managed.take(maxLines)

            val isRunning = managed.process.isAlive
            if (!isRunning) processes.remove(processId)

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

    private fun evictExpiredProcesses() {
        val now = System.currentTimeMillis()
        processes.entries.removeIf { (_, managed) ->
            !managed.process.isAlive && now - managed.startedAt >= completedRetentionMs
        }
    }

    override fun close() {
        cleanupExecutor.shutdownNow()
        processes.values.forEach { managed ->
            if (managed.process.isAlive) destroyTree(managed.process)
        }
        processes.clear()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }

    private fun destroyTree(process: Process) {
        try {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
        } catch (_: Exception) {
            // Best effort: fall through to killing the root process.
        }
        process.destroyForcibly()
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
