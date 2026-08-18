package pl.jclab.refio.core.context.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import pl.jclab.refio.core.logging.dualLogger
import java.io.BufferedWriter
import java.io.OutputStreamWriter

private val transportLogger = dualLogger("MCPStdioTransport")

/**
 * stdio transport for MCP servers.
 */
class MCPStdioTransport(
    private val config: MCPServerConfig,
    private val onMessage: (String) -> Unit,
    private val onError: (Exception) -> Unit
) {
    private var process: Process? = null
    private var readerJob: Job? = null
    private var stderrJob: Job? = null
    private var scope: CoroutineScope? = null
    private var writer: BufferedWriter? = null

    /**
     * Guards the whole message + newline + flush sequence. Read-only tools run in parallel, so
     * several requests can reach [send] at once; without this the parts of two messages end up on
     * one line and the server drops both requests.
     */
    private val writeLock = Any()

    companion object {
        /** Allowed characters in environment variable names (POSIX-safe) */
        private val ENV_NAME_PATTERN = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

        /** Characters that could enable shell injection in env values */
        private val DANGEROUS_VALUE_CHARS = Regex("[`\$\\\\;|&]")
    }

    suspend fun connect() {
        val command = config.command ?: throw IllegalArgumentException("stdio transport requires command")

        val processBuilder = ProcessBuilder().apply {
            command(listOf(command) + config.args)
            config.workingDirectory?.let { directory(java.io.File(it)) }
            redirectErrorStream(false)
            config.env.forEach { envVar ->
                if (envVar.name.isNotBlank()) {
                    // Validate env var name (POSIX-safe characters only)
                    if (!ENV_NAME_PATTERN.matches(envVar.name)) {
                        transportLogger.warn { "[${config.id}] Skipping env var with invalid name: '${envVar.name}'" }
                        return@forEach
                    }
                    // Warn on potentially dangerous values (but still allow — MCP servers may need them)
                    if (DANGEROUS_VALUE_CHARS.containsMatchIn(envVar.value)) {
                        transportLogger.warn { "[${config.id}] Env var '${envVar.name}' contains shell metacharacters" }
                    }
                    environment()[envVar.name] = envVar.value
                }
            }
        }

        try {
            process = processBuilder.start()
            writer = BufferedWriter(OutputStreamWriter(process?.outputStream))

            scope = CoroutineScope(Dispatchers.IO)
            readerJob = scope?.launch {
                process?.inputStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            onMessage(line)
                        }
                    }
                }
            }
            stderrJob = scope?.launch {
                process?.errorStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            transportLogger.debug { "[${config.id}] $line" }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            onError(e)
            disconnect()
            throw MCPTransportException("Failed to start MCP stdio process", e)
        }
    }

    fun send(message: String) {
        try {
            synchronized(writeLock) {
                writer?.apply {
                    write(message)
                    write("\n")
                    flush()
                } ?: throw MCPTransportException("Transport not connected")
            }
        } catch (e: Exception) {
            onError(MCPTransportException("Failed to send MCP message", e))
        }
    }

    fun disconnect() {
        readerJob?.cancel()
        stderrJob?.cancel()
        scope?.cancel()

        // Close writer before destroying process to flush pending data
        try {
            writer?.close()
        } catch (_: Exception) {
            // Ignore — process may already be dead
        }
        writer = null

        val proc = process
        if (proc != null) {
            proc.destroy()
            try {
                // Give process 3 seconds to exit gracefully, then force-kill
                val exited = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                if (!exited) {
                    transportLogger.warn { "[${config.id}] Process did not exit gracefully, force-killing" }
                    proc.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                proc.destroyForcibly()
            }
        }
        process = null
    }
}
