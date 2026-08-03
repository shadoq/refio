package pl.jclab.refio.core.tools.implementations

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.subagents.models.SubagentDefinition
import pl.jclab.refio.core.subagents.models.SubagentInfo
import pl.jclab.refio.core.subagents.models.SubagentScope
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManageSubagentToolTest {

    private lateinit var router: SubagentRouter
    private lateinit var tool: ManageSubagentTool

    @BeforeEach
    fun setup() {
        router = mockk(relaxed = true)
        tool = ManageSubagentTool { router }
    }

    @Test
    fun `has correct metadata`() {
        assertEquals("manage_subagent", tool.name)
        assertEquals(ToolMode.READ_ONLY, tool.mode)
        assertEquals(ToolCategory.SYSTEM, tool.category)
    }

    @Test
    fun `create temporary subagent`() = runBlocking {
        every { router.getSubagent("test-agent") } returns null

        val result = tool.execute(mapOf(
            "action" to "create",
            "name" to "test-agent",
            "description" to "A test agent",
            "system_prompt" to "You are a test agent.",
            "scope" to "temporary",
            "tools" to listOf("read_file", "grep_search"),
            "_mode" to "AGENT"
        ))

        assertTrue(result.success)
        assertTrue(result.output!!.contains("temporary"))
        assertTrue(result.output!!.contains("test-agent"))
        verify { router.registerTemporary(any()) }
    }

    @Test
    fun `create fails with invalid name`() = runBlocking {
        val result = tool.execute(mapOf(
            "action" to "create",
            "name" to "BAD NAME",
            "description" to "Test",
            "system_prompt" to "Test",
            "_mode" to "AGENT"
        ))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("kebab-case"))
    }

    @Test
    fun `repeating an identical create is a no-op success, not a wasted iteration`() = runBlocking {
        // "An agent by this name must exist" is a desired state for the model, so re-issuing the
        // same create must not cost it a turn on an error it cannot learn anything from.
        every { router.getSubagent("existing") } returns SubagentDefinition(
            name = "existing",
            description = "Test",
            systemPrompt = "Test prompt",
            allowedTools = null,
            model = "inherit",
            maxSteps = 25,
            scope = SubagentScope.TEMPORARY
        )

        val result = tool.execute(mapOf(
            "action" to "create",
            "name" to "existing",
            "description" to "Test",
            "system_prompt" to "Test prompt",
            "_mode" to "AGENT"
        ))

        assertTrue(result.success)
        assertTrue(result.output!!.contains("unchanged"))
        assertTrue(result.output!!.contains("invoke_subagent"))
        verify(exactly = 0) { router.registerTemporary(any()) }
    }

    @Test
    fun `create over an existing agent with a changed definition applies it as an update`() = runBlocking {
        every { router.getSubagent("existing") } returns SubagentDefinition(
            name = "existing",
            description = "Old description",
            systemPrompt = "Old prompt",
            model = "inherit",
            maxSteps = 25,
            scope = SubagentScope.TEMPORARY
        )

        val result = tool.execute(mapOf(
            "action" to "create",
            "name" to "existing",
            "description" to "New description",
            "system_prompt" to "New prompt",
            "_mode" to "AGENT"
        ))

        assertTrue(result.success)
        assertTrue(result.output!!.contains("update"))
        verify {
            router.registerTemporary(match {
                it.description == "New description" && it.systemPrompt == "New prompt"
            })
        }
    }

    @Test
    fun `create still refuses to shadow a builtin agent`() = runBlocking {
        every { router.getSubagent("code-reviewer") } returns SubagentDefinition(
            name = "code-reviewer",
            description = "Built-in",
            systemPrompt = "Test",
            scope = SubagentScope.BUILTIN
        )

        val result = tool.execute(mapOf(
            "action" to "create",
            "name" to "code-reviewer",
            "description" to "Mine",
            "system_prompt" to "Mine",
            "_mode" to "AGENT"
        ))

        assertFalse(result.success)
        assertTrue(result.error!!.contains("builtin"))
    }

    @Test
    fun `create enforces tool ceiling in PLAN mode`() = runBlocking {
        every { router.getSubagent("planner") } returns null

        val result = tool.execute(mapOf(
            "action" to "create",
            "name" to "planner",
            "description" to "Plan agent",
            "system_prompt" to "Test",
            "scope" to "temporary",
            "tools" to listOf("read_file", "code_editing", "grep_search", "run_terminal_command"),
            "_mode" to "PLAN"
        ))

        assertTrue(result.success)
        // Verify registerTemporary was called — the tools should be filtered
        verify {
            router.registerTemporary(match { def ->
                val tools = def.allowedTools!!
                tools.contains("read_file") && tools.contains("grep_search") &&
                    !tools.contains("code_editing") && !tools.contains("run_terminal_command")
            })
        }
    }

    @Test
    fun `delete temporary subagent`() = runBlocking {
        every { router.getSubagent("temp-agent") } returns SubagentDefinition(
            name = "temp-agent",
            description = "Temp",
            systemPrompt = "Test",
            scope = SubagentScope.TEMPORARY
        )
        every { router.deleteSubagent("temp-agent") } returns true

        val result = tool.execute(mapOf(
            "action" to "delete",
            "name" to "temp-agent"
        ))
        assertTrue(result.success)
        assertTrue(result.output!!.contains("Deleted"))
    }

    @Test
    fun `delete builtin subagent fails`() = runBlocking {
        every { router.getSubagent("builtin-agent") } returns SubagentDefinition(
            name = "builtin-agent",
            description = "Built-in",
            systemPrompt = "Test",
            scope = SubagentScope.BUILTIN
        )

        val result = tool.execute(mapOf(
            "action" to "delete",
            "name" to "builtin-agent"
        ))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("builtin"))
    }

    @Test
    fun `list subagents`() = runBlocking {
        every { router.listSubagents(includeDisabled = true) } returns listOf(
            SubagentInfo("agent-a", "Agent A desc", null, "default", true, "BUILTIN"),
            SubagentInfo("agent-b", "Agent B desc", listOf("read_file"), "inherit", true, "TEMPORARY")
        )

        val result = tool.execute(mapOf("action" to "list"))
        assertTrue(result.success)
        assertTrue(result.output!!.contains("agent-a"))
        assertTrue(result.output!!.contains("agent-b"))
        assertTrue(result.output!!.contains("BUILTIN"))
        assertTrue(result.output!!.contains("TEMPORARY"))
    }

    @Test
    fun `fails without action and names every valid action`() = runBlocking {
        // Providers do not enforce the schema's `required`, so this error IS the model's only hint.
        val result = tool.execute(emptyMap())

        assertFalse(result.success)
        listOf("create", "update", "delete", "list").forEach {
            assertTrue(result.error!!.contains(it), "missing action '$it' in: ${result.error}")
        }
    }

    @Test
    fun `update temporary subagent`() = runBlocking {
        every { router.getSubagent("my-agent") } returns SubagentDefinition(
            name = "my-agent",
            description = "Original",
            systemPrompt = "Original prompt",
            scope = SubagentScope.TEMPORARY
        )

        val result = tool.execute(mapOf(
            "action" to "update",
            "name" to "my-agent",
            "description" to "Updated description",
            "_mode" to "AGENT"
        ))

        assertTrue(result.success)
        verify {
            router.registerTemporary(match { it.description == "Updated description" })
        }
    }
}
