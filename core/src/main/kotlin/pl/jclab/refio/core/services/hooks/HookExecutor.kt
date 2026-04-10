package pl.jclab.refio.core.services.hooks

import pl.jclab.refio.core.logging.dualLogger
import java.util.concurrent.TimeUnit

private val logger = dualLogger("HookExecutor")

class HookExecutor {

    data class CommandResult(
        val success: Boolean,
        val output: String? = null,
        val error: String? = null
    )

    fun runCommand(
        command: String,
        variables: Map<String, String>,
        timeoutMs: Long = 30_000
    ): CommandResult {
        val substituted = substituteVariables(command, variables)
        return try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val processBuilder = if (isWindows) {
                ProcessBuilder("cmd", "/c", substituted)
            } else {
                ProcessBuilder("sh", "-c", substituted)
            }
            processBuilder.redirectErrorStream(false)

            val process = processBuilder.start()

            // Read streams in separate threads to avoid deadlock when OS pipe buffer fills
            var stdout = ""
            var stderr = ""
            val stdoutThread = Thread {
                stdout = process.inputStream.bufferedReader().readText().trim()
            }
            val stderrThread = Thread {
                stderr = process.errorStream.bufferedReader().readText().trim()
            }
            stdoutThread.start()
            stderrThread.start()

            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

            if (!completed) {
                process.destroyForcibly()
                stdoutThread.interrupt()
                stderrThread.interrupt()
                logger.warn { "[HOOK] Command timed out after ${timeoutMs}ms: $substituted" }
                return CommandResult(success = false, error = "Timeout after ${timeoutMs}ms")
            }

            stdoutThread.join(5000)
            stderrThread.join(5000)

            val exitCode = process.exitValue()

            if (exitCode != 0) {
                logger.warn { "[HOOK] Command exited with code $exitCode: $substituted, stderr: $stderr" }
                CommandResult(success = false, output = stdout, error = "Exit code $exitCode: $stderr")
            } else {
                CommandResult(success = true, output = stdout)
            }
        } catch (e: Exception) {
            logger.warn { "[HOOK] Command execution failed: ${e.message}" }
            CommandResult(success = false, error = e.message)
        }
    }

    fun notify(
        message: String,
        variables: Map<String, String>,
        callback: (String) -> Unit
    ) {
        val substituted = substituteVariables(message, variables)
        try {
            callback(substituted)
        } catch (e: Exception) {
            logger.warn { "[HOOK] Notify callback failed: ${e.message}" }
        }
    }

    private fun substituteVariables(template: String, variables: Map<String, String>): String {
        var result = template
        for ((key, value) in variables) {
            result = result.replace("{$key}", value)
        }
        return result
    }
}
