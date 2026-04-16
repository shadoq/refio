package pl.jclab.refio.core.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.logging.dualLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = dualLogger("ProcessManager")

/**
 * Manages long-running background processes.
 * Processes are stored by process_id and can be monitored or stopped.
 */
class ProcessManager {
    data class ManagedProcess(
        val processId: String,
        val command: String,
        val process: Process,
        val startedAt: Long = System.currentTimeMillis()
    )

    private val processes = ConcurrentHashMap<String, ManagedProcess>()

    fun start(command: String, workingDir: java.io.File): ManagedProcess {
        val processId = UUID.randomUUID().toString().take(8)
        val pb = ProcessBuilder()
            .command(shellWrap(command))
            .directory(workingDir)
            .redirectErrorStream(true)
        val process = pb.start()
        val managed = ManagedProcess(processId, command, process)
        processes[processId] = managed
        logger.info { "Started background process $processId: $command" }
        return managed
    }

    fun get(processId: String): ManagedProcess? = processes[processId]

    fun stop(processId: String) {
        val managed = processes.remove(processId) ?: return
        managed.process.destroyForcibly()
        logger.info { "Stopped process $processId" }
    }

    fun listRunning(): List<ManagedProcess> =
        processes.values.filter { it.process.isAlive }.toList()

    suspend fun readOutput(processId: String, maxLines: Int = 500): Pair<List<String>, Boolean> =
        withContext(Dispatchers.IO) {
            val managed = processes[processId]
                ?: return@withContext Pair(emptyList(), false)

            val lines = mutableListOf<String>()
            val reader = managed.process.inputStream.bufferedReader()

            while (lines.size < maxLines && reader.ready()) {
                val line = reader.readLine() ?: break
                lines.add(line)
            }

            val isRunning = managed.process.isAlive
            if (!isRunning) processes.remove(processId)

            Pair(lines, isRunning)
        }

    private fun shellWrap(cmd: String): List<String> =
        if (System.getProperty("os.name").lowercase().contains("windows"))
            listOf("cmd.exe", "/c", cmd)
        else
            listOf("/bin/sh", "-c", cmd)
}
