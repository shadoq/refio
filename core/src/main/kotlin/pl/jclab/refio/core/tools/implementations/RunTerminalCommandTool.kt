package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.security.CommandLimits
import pl.jclab.refio.core.tools.security.CommandRuleMatcher
import pl.jclab.refio.core.tools.security.RuleAction
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.jclab.refio.core.logging.dualLogger
import java.util.concurrent.TimeUnit

private val logger = dualLogger("RunTerminalCommandTool")

/**
 * Run Terminal Command Tool - executes shell commands
 *
 * Parameters:
 * - command: Shell command to execute
 *
 * Security:
 * - Command rule matcher (regex-based ALLOW/BLOCK/ASK) validates commands
 * - Execution timeout enforced
 * - Output size limits
 * - Runs in project root directory
 */
class RunTerminalCommandTool(
    private val sandbox: PathSandbox,
    private val limits: CommandLimits,
    private val commandRuleMatcher: CommandRuleMatcher
) : Tool {

    override val name = "run_terminal_command"
    override val description = "Execute a shell command in the project directory."
    override val mode = ToolMode.WRITE
    override val category = ToolCategory.EXECUTION
    override val selectionHint =
        "OS-level commands: git, gradle, npm, docker, build/test runners. " +
        "Avoid inline `python -c \"...\"` on Windows — prefer run_code when available."

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

            // Optional timeout override from LLM (clamped to safe bounds)
            val effectiveTimeout = (params["timeout_seconds"] as? Number)?.toLong()
                ?.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
                ?: limits.timeoutSeconds

            // Check command rules (regex-based ALLOW/BLOCK/ASK)
            val ruleResult = commandRuleMatcher.match(command)
            when (ruleResult.action) {
                RuleAction.BLOCK -> {
                    val desc = ruleResult.matchedRule?.description ?: "blocked by security policy"
                    logger.warn { "Blocked command by rule: reason='$desc', command='$command'" }
                    return@withContext ToolResult.error("Command blocked: $desc")
                }
                RuleAction.ALLOW -> {
                    logger.debug { "Command allowed by rule: ${ruleResult.matchedRule?.description}, command='$command'" }
                }
                RuleAction.ASK -> {
                    // ASK is handled at TurnToolExecutor level (PermissionLevel.ASK).
                    // At tool level, we allow execution — the approval already happened.
                    logger.debug { "Command ASK rule (pre-approved): ${ruleResult.matchedRule?.description}, command='$command'" }
                }
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
            val completed = process.waitFor(effectiveTimeout, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                // Capture partial output instead of discarding it
                val partialOutput = withTimeoutOrNull(3000L) {
                    runCatching { outputDeferred.await() }.getOrDefault("")
                } ?: ""
                val duration = (System.currentTimeMillis() - startTime).toInt()

                logger.warn { "Command timed out after ${effectiveTimeout}s: $command, partial output=${partialOutput.length} chars" }

                val truncatedPartial = if (partialOutput.length > limits.maxOutputSize) {
                    partialOutput.take(limits.maxOutputSize) + "\n\n... (output truncated)"
                } else {
                    partialOutput
                }

                val message = buildString {
                    append("Command timed out after $effectiveTimeout seconds.")
                    if (truncatedPartial.isNotBlank()) {
                        append("\n\nPartial output before timeout:\n")
                        append(truncatedPartial)
                    }
                }

                return@withContext ToolResult(
                    success = false,
                    output = message,
                    exitCode = -1,
                    durationMs = duration,
                    metadata = mapOf(
                        "command" to command,
                        "timed_out" to true,
                        "timeout_seconds" to effectiveTimeout,
                        "partial_output_length" to partialOutput.length
                    )
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
                ),
                "timeout_seconds" to mapOf(
                    "type" to "integer",
                    "description" to "Timeout in seconds (default: ${limits.timeoutSeconds}, max: $MAX_TIMEOUT_SECONDS)."
                )
            ),
            "required" to listOf("command")
        )
    }

    companion object {
        const val MIN_TIMEOUT_SECONDS = 30L
        const val MAX_TIMEOUT_SECONDS = 600L
    }
}
