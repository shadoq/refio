package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.security.CommandDenylist
import pl.jclab.refio.core.tools.security.CommandLimits
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
 * - Command denylist blocks dangerous commands
 * - Execution timeout enforced
 * - Output size limits
 * - Runs in project root directory
 */
class RunTerminalCommandTool(
    private val sandbox: PathSandbox,
    private val denylist: CommandDenylist,
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
            // Extract parameters (already validated)
            val command = params["command"] as String

            // Check denylist
            if (denylist.isBlocked(command)) {
                logger.warn { "Blocked dangerous command: $command" }
                return@withContext ToolResult.error(
                    "Command is blocked for security reasons: $command"
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

            // Wait with timeout
            val completed = process.waitFor(limits.timeoutSeconds, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                logger.warn { "Command timed out after ${limits.timeoutSeconds}s: $command" }
                return@withContext ToolResult.error(
                    "Command timed out after ${limits.timeoutSeconds} seconds"
                )
            }

            // Read output
            val output = process.inputStream.bufferedReader().readText()
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
            os.contains("windows") -> listOf("powershell.exe", "-Command", command)
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
