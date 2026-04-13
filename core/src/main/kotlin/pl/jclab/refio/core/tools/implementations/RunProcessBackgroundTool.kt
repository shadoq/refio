package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.services.ProcessManager
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.security.CommandRuleMatcher
import pl.jclab.refio.core.tools.security.CommandWhitelist
import pl.jclab.refio.core.tools.security.RuleAction

class RunProcessBackgroundTool(
    private val sandbox: PathSandbox,
    private val processManager: ProcessManager,
    private val whitelist: CommandWhitelist,
    private val commandRuleMatcher: CommandRuleMatcher? = null
) : Tool {
    override val name = "run_process_background"
    override val description = "Start a command in the background and return immediately with a process_id. " +
        "Use monitor_process(process_id) to read output. " +
        "Use when you need to run long commands (build, test, dev server) without blocking."
    override val mode = ToolMode.WRITE
    override val category = ToolCategory.EXECUTION

    override fun validateParams(params: Map<String, Any>) {
        val cmd = params["command"] as? String
        if (cmd.isNullOrBlank()) throw IllegalArgumentException("Parameter 'command' is required")
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val command = params["command"] as? String
            ?: return ToolResult.error("Missing 'command'")

        val ruleAction = commandRuleMatcher?.match(command)?.action
        if (ruleAction == RuleAction.BLOCK) {
            return ToolResult.error("Command blocked by security rules: $command")
        }

        val workingDir = sandbox.getProjectRoot().toAbsolutePath().toFile()
        val managed = try {
            processManager.start(command, workingDir)
        } catch (e: Exception) {
            return ToolResult.error("Failed to start process: ${e.message}")
        }

        return ToolResult(
            success = true,
            output = "Process started with id: ${managed.processId}\n" +
                "Command: $command\n" +
                "Use monitor_process(process_id=\"${managed.processId}\") to read output.",
            metadata = mapOf(
                "process_id" to managed.processId,
                "command" to command
            )
        )
    }

    override fun getParameterSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "command" to mapOf("type" to "string", "description" to "Shell command to run in background")
        ),
        "required" to listOf("command")
    )
}
