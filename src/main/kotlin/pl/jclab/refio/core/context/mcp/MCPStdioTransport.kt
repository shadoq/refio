package pl.jclab.refio.core.context.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import pl.jclab.refio.services.logging.dualLogger
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

    suspend fun connect() {
        val command = config.command ?: throw IllegalArgumentException("stdio transport requires command")

        val processBuilder = ProcessBuilder().apply {
            command(listOf(command) + config.args)
            config.workingDirectory?.let { directory(java.io.File(it)) }
            redirectErrorStream(false)
            config.env.forEach { envVar ->
                if (envVar.name.isNotBlank()) {
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
            writer?.apply {
                write(message)
                write("\n")
                flush()
            } ?: throw MCPTransportException("Transport not connected")
        } catch (e: Exception) {
            onError(MCPTransportException("Failed to send MCP message", e))
        }
    }

    fun disconnect() {
        readerJob?.cancel()
        stderrJob?.cancel()
        scope?.cancel()
        writer = null

        process?.destroy()
        process = null
    }
}
