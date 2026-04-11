package pl.jclab.refio.core.tools.implementations

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.services.context.WorkingMemoryService
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryToolTest {

    private lateinit var memoryService: WorkingMemoryService
    private lateinit var tool: MemoryTool

    @BeforeEach
    fun setup() {
        memoryService = WorkingMemoryService()
        tool = MemoryTool(memoryService)
    }

    @Test
    fun `memory tool has correct metadata`() {
        assertEquals("memory", tool.name)
        assertEquals(ToolMode.READ_ONLY, tool.mode)
        assertEquals(ToolCategory.SYSTEM, tool.category)
    }

    @Test
    fun `write action stores fact with agent prefix`() = runBlocking {
        val result = tool.execute(mapOf(
            "_task_id" to "task-1",
            "_agent_name" to "searcher",
            "action" to "write",
            "key" to "findings",
            "value" to "Found 5 important emails",
            "importance" to 9
        ))

        assertTrue(result.success)
        assertTrue(result.output!!.contains("agent:searcher:findings"))

        val section = memoryService.buildWorkingMemorySection("task-1", 4096)
        assertTrue(section.contains("agent:searcher:findings"))
        assertTrue(section.contains("Found 5 important emails"))
    }

    @Test
    fun `write action stores fact without agent prefix`() = runBlocking {
        val result = tool.execute(mapOf(
            "_task_id" to "task-1",
            "action" to "write",
            "key" to "decisions",
            "value" to "Use HTTP approach"
        ))

        assertTrue(result.success)
        val section = memoryService.buildWorkingMemorySection("task-1", 4096)
        assertTrue(section.contains("decisions"))
    }

    @Test
    fun `write action clamps importance`() = runBlocking {
        val result = tool.execute(mapOf(
            "_task_id" to "task-1",
            "action" to "write",
            "key" to "test",
            "value" to "Test value",
            "importance" to 15
        ))

        assertTrue(result.success)
        assertTrue(result.output!!.contains("importance: 10"))
    }

    @Test
    fun `write action fails without key`() = runBlocking {
        val result = tool.execute(mapOf(
            "_task_id" to "task-1",
            "action" to "write",
            "value" to "test"
        ))
        assertFalse(result.success)
    }

    @Test
    fun `read action shows all memory`() = runBlocking {
        tool.execute(mapOf(
            "_task_id" to "task-1",
            "_agent_name" to "searcher",
            "action" to "write",
            "key" to "findings",
            "value" to "Email from Wiktor"
        ))
        tool.execute(mapOf(
            "_task_id" to "task-1",
            "_agent_name" to "analyzer",
            "action" to "write",
            "key" to "analysis",
            "value" to "Contains password"
        ))

        val result = tool.execute(mapOf(
            "_task_id" to "task-1",
            "action" to "read"
        ))
        assertTrue(result.success)
        assertTrue(result.output!!.contains("Email from Wiktor"))
        assertTrue(result.output!!.contains("Contains password"))
    }

    @Test
    fun `read action filters by prefix`() = runBlocking {
        tool.execute(mapOf(
            "_task_id" to "task-1",
            "_agent_name" to "searcher",
            "action" to "write",
            "key" to "findings",
            "value" to "Found emails"
        ))
        tool.execute(mapOf(
            "_task_id" to "task-1",
            "_agent_name" to "analyzer",
            "action" to "write",
            "key" to "analysis",
            "value" to "Pattern found"
        ))

        val result = tool.execute(mapOf(
            "_task_id" to "task-1",
            "action" to "read",
            "filter" to "agent:searcher"
        ))
        assertTrue(result.success)
        assertTrue(result.output!!.contains("Found emails"))
        assertFalse(result.output!!.contains("Pattern found"))
    }

    @Test
    fun `read action shows empty message`() = runBlocking {
        val result = tool.execute(mapOf(
            "_task_id" to "task-1",
            "action" to "read"
        ))
        assertTrue(result.success)
        assertTrue(result.output!!.contains("empty"))
    }

    @Test
    fun `list action shows memory keys`() = runBlocking {
        tool.execute(mapOf(
            "_task_id" to "task-1",
            "action" to "write",
            "key" to "findings",
            "value" to "Something"
        ))

        val result = tool.execute(mapOf(
            "_task_id" to "task-1",
            "action" to "list"
        ))
        assertTrue(result.success)
        assertTrue(result.output!!.contains("findings"))
    }

    @Test
    fun `fails without action`() = runBlocking {
        val result = tool.execute(mapOf("_task_id" to "task-1"))
        assertFalse(result.success)
    }
}
