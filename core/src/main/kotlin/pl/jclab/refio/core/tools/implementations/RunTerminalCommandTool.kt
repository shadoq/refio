package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.security.CommandLimits
import pl.jclab.refio.core.tools.security.CommandRuleMatcher
import pl.jclab.refio.core.tools.security.RuleAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ProcessTreeTracker
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
            val processBuilder = ProcessBuilder()
                .command(shellCommand)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
            // Force UTF-8 stdio for child processes (e.g. `python -c`) so non-ASCII output
            // is not mangled by the platform default code page before the JVM reads UTF-8.
            processBuilder.environment().apply {
                put("PYTHONUTF8", "1")
                put("PYTHONIOENCODING", "utf-8")
            }
            val process = processBuilder.start()

            // Retain the descendant tree while the process is alive. A backgrounded child (e.g.
            // `python app.py &`) shares stdout with the shell; a non-interactive `sh -c` has no job
            // control, so `kill %1` in the command is a no-op and the child survives as an orphan
            // holding the stdout pipe open. The tracker lets us reap that orphan later even after it
            // reparents away from the shell on exit.
            val processTree = ProcessTreeTracker(process, threadName = "rtc-descendants")

            // Drain stdout/stderr on a dedicated thread so we never block the coroutine indefinitely.
            // Decode as UTF-8 explicitly: the Windows console emits OEM/ANSI bytes that the JVM
            // (default UTF-8 since JEP 400) would otherwise turn into replacement characters.
            val outputBuffer = StringBuilder()
            val reader = thread(isDaemon = true, name = "rtc-reader") {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { br ->
                        val buffer = CharArray(READER_CHUNK_CHARS)
                        while (true) {
                            val read = br.read(buffer)
                            if (read < 0) {
                                break
                            }
                            synchronized(outputBuffer) {
                                outputBuffer.append(buffer, 0, read)
                            }
                        }
                    }
                } catch (streamClosed: Exception) {
                    // Stream was closed (e.g. process tree destroyed) - stop draining.
                }
            }

            fun snapshotOutput(): String = synchronized(outputBuffer) { outputBuffer.toString() }

            // Wait for the shell to finish within the wall-clock timeout.
            val completed = process.waitFor(effectiveTimeout, TimeUnit.SECONDS)
            processTree.close()

            if (!completed) {
                // Timeout: kill the whole tree (shell + any orphaned child) so nothing lingers,
                // then collect whatever output was captured before the deadline.
                processTree.destroyTree()
                reader.join(READER_FINAL_GRACE_MS)
                val partialOutput = snapshotOutput()
                val duration = (System.currentTimeMillis() - startTime).toInt()

                logger.warn { "Command timed out after ${effectiveTimeout}s: $command, partial output=${partialOutput.length} chars; process tree killed" }

                val truncatedPartial = if (partialOutput.length > limits.maxOutputSize) {
                    partialOutput.take(limits.maxOutputSize) + "\n\n... (output truncated)"
                } else {
                    partialOutput
                }

                val message = buildString {
                    append("Command timed out after $effectiveTimeout seconds; process tree killed ")
                    append("(a backgrounded server may have kept output open).")
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

            // The shell exited, but a backgrounded orphan may still hold the stdout pipe open,
            // which would block the reader forever. Give the reader a short grace to flush buffered
            // output; if it is still blocked, reap the surviving tree so the pipe closes.
            reader.join(READER_DRAIN_GRACE_MS)
            val orphanReaped = reader.isAlive
            if (orphanReaped) {
                logger.warn { "Command exited but left a child holding stdout open: $command; killing surviving process tree" }
                processTree.destroyTree()
                reader.join(READER_FINAL_GRACE_MS)
            }

            val output = snapshotOutput()
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
                error = if (exitCode == 0) null else describeFailure(command, exitCode, truncatedOutput),
                exitCode = exitCode,
                durationMs = duration,
                metadata = mapOf(
                    "command" to command,
                    "exit_code" to exitCode,
                    "output_length" to output.length,
                    "truncated" to (output.length > limits.maxOutputSize),
                    "orphan_reaped" to orphanReaped
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
    /**
     * Builds the failure text for a command that exited non-zero.
     *
     * A non-zero exit is not always a malfunction: `grep -c` exits 1 while printing a perfectly
     * valid count of `0`. Passing the raw output on as the failure reason turns that answer into
     * a bare "Error: 0" and drops the exit code, the one value that tells the two cases apart.
     */
    private fun describeFailure(command: String, exitCode: Int, output: String): String = buildString {
        append("Command exited with code ").append(exitCode).append(": ").append(command)
        if (output.isNotBlank()) {
            append("\nOutput:\n").append(output)
        }
    }

    private fun getShellCommand(command: String): List<String> {
        val os = System.getProperty("os.name").lowercase()

        return when {
            os.contains("windows") -> listOf(
                "powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                WINDOWS_UTF8_PREFIX + command
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

        // Read buffer size for draining process output.
        private const val READER_CHUNK_CHARS = 8192

        // Grace given to the reader to flush buffered output after the shell exits, before we
        // treat a still-blocked reader as a sign of a surviving orphan and reap the tree.
        private const val READER_DRAIN_GRACE_MS = 2000L

        // Grace given to the reader to reach EOF after the process tree has been destroyed.
        private const val READER_FINAL_GRACE_MS = 3000L

        /**
         * Forces UTF-8 for PowerShell output so non-ASCII (e.g. Polish) characters are not
         * mangled by the console's default OEM/ANSI code page before the JVM reads them as UTF-8.
         * The [Console]::OutputEncoding assignment is wrapped in try/catch because it can throw
         * when stdout is redirected; $OutputEncoding alone still covers the pipeline to native tools.
         */
        private const val WINDOWS_UTF8_PREFIX =
            "\$OutputEncoding = [System.Text.Encoding]::UTF8; " +
            "try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}; "
    }
}
