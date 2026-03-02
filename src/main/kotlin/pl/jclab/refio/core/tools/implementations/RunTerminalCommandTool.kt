package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.security.CommandLimits
import pl.jclab.refio.core.tools.security.CommandWhitelist
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.services.logging.dualLogger
import java.util.concurrent.TimeUnit

private val logger = dualLogger("RunTerminalCommandTool")

/**
 * Run Terminal Command Tool - executes shell commands
 *
 * Parameters:
 * - command: Shell command to execute
 *
 * Security:
 * - Command whitelist validates allowed programs/arguments
 * - Execution timeout enforced
 * - Output size limits
 * - Runs in project root directory
 */
class RunTerminalCommandTool(
    private val sandbox: PathSandbox,
    private val whitelist: CommandWhitelist,
    private val limits: CommandLimits
) : Tool {

    override val name = "run_terminal_command"
    override val description = "Execute shell commands in the project directory"
    override val mode = ToolMode.WRITE
    override val category = ToolCategory.EXECUTION

    override fun validateParams(params: Map<String, Any>) {
        if (params["command"] == null || (params["command"] as? String).isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'command' is required and cannot be empty")
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            // Extract parameters with safe casting
            val command = params["command"] as? String
                ?: return@withContext ToolResult.error("Missing required parameter: 'command'")

            val validation = whitelist.validate(command)
            if (!validation.allowed) {
                logger.warn { "Blocked command by whitelist: reason='${validation.reason}', command='$command'" }
                return@withContext ToolResult.error(
                    "Command not allowed: ${validation.reason ?: "blocked"}"
                )
            }

            if (validation.requiresConfirmation) {
                logger.warn { "Command requires user confirmation: $command" }
                return@withContext ToolResult.error(
                    "Command requires user confirmation: $command"
                )
            }

            // Prepare shell command
            val shellCommand = getShellCommand(command)
            val workingDir = sandbox.resolve(".").toAbsolutePath()

            logger.info { "Executing command: '$command', workingDir='$workingDir', shell=${shellCommand[0]}" }

            // Execute command
            val process = ProcessBuilder()
                .command(shellCommand)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start()

            // Read process output concurrently to avoid stdout buffer deadlocks on large listings.
            val outputDeferred = async(Dispatchers.IO) {
                process.inputStream.bufferedReader().use { it.readText() }
            }

            // Wait with timeout
            val completed = process.waitFor(limits.timeoutSeconds, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                runCatching { process.inputStream.close() }
                outputDeferred.cancel()
                logger.warn { "Command timed out after ${limits.timeoutSeconds}s: $command" }
                return@withContext ToolResult.error(
                    "Command timed out after ${limits.timeoutSeconds} seconds"
                )
            }

            val output = runCatching { outputDeferred.await() }.getOrDefault("")
            val exitCode = process.exitValue()
            val duration = (System.currentTimeMillis() - startTime).toInt()

            // Limit output size
            val truncatedOutput = if (output.length > limits.maxOutputSize) {
                val truncated = output.take(limits.maxOutputSize)
                "$truncated\n\n... (output truncated to ${limits.maxOutputSize} characters)"
            } else {
                output
            }

            logger.info { "Command completed: exitCode=$exitCode, output=${output.length} chars, ${duration}ms" }

            return@withContext ToolResult(
                success = exitCode == 0,
                output = truncatedOutput,
                exitCode = exitCode,
                durationMs = duration,
                metadata = mapOf(
                    "command" to command,
                    "exit_code" to exitCode,
                    "output_length" to output.length,
                    "truncated" to (output.length > limits.maxOutputSize)
                )
            )

        } catch (e: Exception) {
            logger.error(e) { "Failed to execute command" }
            return@withContext ToolResult.error("Command execution failed: ${e.message}")
        }
    }

    /**
     * Get shell command for current OS
     */
    private fun getShellCommand(command: String): List<String> {
        val os = System.getProperty("os.name").lowercase()

        return when {
            os.contains("windows") -> listOf(
                "powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                command
            )
            else -> listOf("/bin/sh", "-c", command)
        }
    }

    override fun getParameterSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "properties" to mapOf(
                "command" to mapOf(
                    "type" to "string",
                    "description" to "Shell command to execute (runs in project root directory)"
                )
            ),
            "required" to listOf("command")
        )
    }
}
