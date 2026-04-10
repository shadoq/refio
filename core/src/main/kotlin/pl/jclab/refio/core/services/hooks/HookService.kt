package pl.jclab.refio.core.services.hooks

import pl.jclab.refio.core.config.HookDefinition
import pl.jclab.refio.core.config.HooksConfig
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("HookService")

class HookService(
    private val configProvider: () -> HooksConfig?,
    private val hookExecutor: HookExecutor,
    private val notifyCallback: (String) -> Unit = {}
) {

    fun trigger(eventName: String, variables: Map<String, String>) {
        val config = configProvider() ?: return
        val hooks = getHooksForEvent(config, eventName) ?: return

        for (hook in hooks) {
            if (!matchesFilter(hook, variables)) continue
            executeHook(hook, variables)
        }
    }

    private fun getHooksForEvent(config: HooksConfig, eventName: String): List<HookDefinition>? {
        return when (eventName) {
            "before_turn_loop" -> config.beforeTurnLoop
            "after_turn_loop" -> config.afterTurnLoop
            "before_tool" -> config.beforeTool
            "after_tool" -> config.afterTool
            "on_agent_complete" -> config.onAgentComplete
            "on_agent_error" -> config.onAgentError
            else -> {
                logger.warn { "[HOOK] Unknown event: $eventName" }
                null
            }
        }
    }

    private fun matchesFilter(hook: HookDefinition, variables: Map<String, String>): Boolean {
        val modes = hook.modes
        if (modes != null && modes.isNotEmpty()) {
            val currentMode = variables["mode"] ?: return true
            if (currentMode !in modes) return false
        }

        val matchPattern = hook.match
        if (matchPattern != null) {
            val toolName = variables["toolName"] ?: return true
            if (!Regex(matchPattern).containsMatchIn(toolName)) return false
        }

        return true
    }

    private fun executeHook(hook: HookDefinition, variables: Map<String, String>) {
        try {
            when (hook.action) {
                "run_command" -> {
                    val command = hook.command
                    if (command == null) {
                        logger.warn { "[HOOK] run_command hook missing 'command' field" }
                        return
                    }
                    val timeout = hook.timeout ?: 30_000L
                    val result = hookExecutor.runCommand(command, variables, timeout)
                    if (!result.success) {
                        logger.warn { "[HOOK] run_command failed: ${result.error}" }
                    }
                }
                "notify" -> {
                    val message = hook.message
                    if (message == null) {
                        logger.warn { "[HOOK] notify hook missing 'message' field" }
                        return
                    }
                    hookExecutor.notify(message, variables, notifyCallback)
                }
                else -> {
                    logger.warn { "[HOOK] Unknown action: ${hook.action}" }
                }
            }
        } catch (e: Exception) {
            logger.warn { "[HOOK] Hook execution failed: ${e.message}" }
        }
    }
}
