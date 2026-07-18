package pl.jclab.refio.core.services.hooks

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import pl.jclab.refio.core.config.HookDefinition
import pl.jclab.refio.core.config.HooksConfig

class HookServiceTest {

    @Test
    fun `trigger before_tool executes matching hooks`() {
        val notifications = mutableListOf<String>()
        val config = HooksConfig(
            beforeTool = listOf(
                HookDefinition(
                    action = "notify",
                    message = "Running {toolName}",
                    match = "code_editing|multi_edit"
                )
            )
        )
        val service = HookService(
            configProvider = { config },
            hookExecutor = HookExecutor(),
            notifyCallback = { notifications.add(it) }
        )

        runBlocking { service.trigger("before_tool", mapOf("toolName" to "code_editing", "taskId" to "t1", "mode" to "AGENT")) }

        assertEquals(1, notifications.size)
        assertEquals("Running code_editing", notifications[0])
    }

    @Test
    fun `trigger before_tool skips non-matching hooks`() {
        val notifications = mutableListOf<String>()
        val config = HooksConfig(
            beforeTool = listOf(
                HookDefinition(
                    action = "notify",
                    message = "Running {toolName}",
                    match = "code_editing"
                )
            )
        )
        val service = HookService(
            configProvider = { config },
            hookExecutor = HookExecutor(),
            notifyCallback = { notifications.add(it) }
        )

        runBlocking { service.trigger("before_tool", mapOf("toolName" to "read_file", "taskId" to "t1", "mode" to "AGENT")) }

        assertEquals(0, notifications.size)
    }

    @Test
    fun `trigger filters by mode`() {
        val notifications = mutableListOf<String>()
        val config = HooksConfig(
            onAgentComplete = listOf(
                HookDefinition(
                    action = "notify",
                    message = "Done in {mode}",
                    modes = listOf("AGENT")
                )
            )
        )
        val service = HookService(
            configProvider = { config },
            hookExecutor = HookExecutor(),
            notifyCallback = { notifications.add(it) }
        )

        runBlocking { service.trigger("on_agent_complete", mapOf("mode" to "PLAN", "taskId" to "t1", "iterations" to "5")) }
        assertEquals(0, notifications.size, "PLAN mode should not match")

        runBlocking { service.trigger("on_agent_complete", mapOf("mode" to "AGENT", "taskId" to "t1", "iterations" to "5")) }
        assertEquals(1, notifications.size, "AGENT mode should match")
    }

    @Test
    fun `trigger with null config does nothing`() {
        val service = HookService(
            configProvider = { null },
            hookExecutor = HookExecutor(),
            notifyCallback = {}
        )

        runBlocking { service.trigger("before_tool", mapOf("toolName" to "read_file")) }
    }

    @Test
    fun `trigger on_agent_error executes hooks`() {
        val notifications = mutableListOf<String>()
        val config = HooksConfig(
            onAgentError = listOf(
                HookDefinition(
                    action = "notify",
                    message = "Error: {error}"
                )
            )
        )
        val service = HookService(
            configProvider = { config },
            hookExecutor = HookExecutor(),
            notifyCallback = { notifications.add(it) }
        )

        runBlocking { service.trigger("on_agent_error", mapOf("error" to "Max iterations exceeded", "mode" to "AGENT", "taskId" to "t1")) }

        assertEquals(1, notifications.size)
        assertEquals("Error: Max iterations exceeded", notifications[0])
    }
}
