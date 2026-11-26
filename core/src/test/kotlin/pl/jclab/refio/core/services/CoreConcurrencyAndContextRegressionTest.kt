package pl.jclab.refio.core.services

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.services.context.ContextBudget
import pl.jclab.refio.core.services.context.ContextLayerCache
import pl.jclab.refio.core.services.context.ContextSection
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.turn.ContextSectionRecord
import pl.jclab.refio.core.subagents.SubagentToolFilter
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolResult
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreConcurrencyAndContextRegressionTest {

    @Test
    fun `AgentEventBus isolates control events from StreamChunk flood`() = runBlocking {
        val bus = AgentEventBus()
        val expected = AgentEvent.DataResponse(
            id = "resp-1",
            sessionId = "s1",
            sourceAgentId = "a1",
            timestamp = 1L,
            correlationId = "c1",
            requestId = "req-1",
            targetAgentId = "a2",
            response = "ok",
        )
        val waiter = async {
            bus.eventsOfType<AgentEvent.DataResponse>().first()
        }
        // Flood high-frequency StreamChunk events well past buffer size
        repeat(1500) { i ->
            bus.tryEmit(
                AgentEvent.StreamChunk(
                    id = "chunk-$i",
                    sessionId = "s1",
                    sourceAgentId = "a1",
                    timestamp = i.toLong(),
                    correlationId = "c1",
                    delta = "x",
                    accumulated = "x",
                    isComplete = false,
                )
            )
        }
        bus.emit(expected)
        assertEquals("req-1", waiter.await().requestId)
    }

    @Test
    fun `SubagentToolFilter enforces disallowedTools even when allowedTools is specified`() {
        val allowed = listOf("read_file", "code_editing", "grep_search")
        val disallowed = listOf("code_editing")

        assertTrue(SubagentToolFilter.isToolAllowedUnderProfile("read_file", allowed, disallowed))
        assertFalse(SubagentToolFilter.isToolAllowedUnderProfile("code_editing", allowed, disallowed))
        assertFalse(SubagentToolFilter.isToolAllowedUnderProfile("run_terminal_command", allowed, disallowed))
    }

    @Test
    fun `ContextBudget redistributeUnused ignores unrendered sections absent from actualUsage`() {
        val budget = ContextBudget(
            totalTokens = 1000,
            sectionBudgets = mapOf(
                ContextSection.PROJECT_CONTEXT to 200,
                ContextSection.RECENT_WORK to 300,
                ContextSection.WORKING_MEMORY to 200,
                ContextSection.CONVERSATION to 300,
            )
        )
        // PROJECT_CONTEXT used its entire 200 budget -> 0 unused slack.
        // Unrendered RECENT_WORK/WORKING_MEMORY/CONVERSATION must NOT inflate themselves.
        val after = budget.redistributeUnused(mapOf(ContextSection.PROJECT_CONTEXT to 200))
        assertEquals(budget, after)
    }

    @Test
    fun `ContextLayerCache bounds entry count and preserves sectionUsage and traceRecords`() {
        val cache = ContextLayerCache(maxEntries = 2)
        val usage = mapOf(ContextSection.PROJECT_CONTEXT to 123)
        val trace = listOf(
            ContextSectionRecord(
                section = ContextSection.PROJECT_CONTEXT,
                priority = ContextSection.PROJECT_CONTEXT.defaultPriority,
                included = true,
                estimatedTokens = 123,
                actualTokens = 123,
            )
        )
        cache.putStableContext("t1", "c1", 123, sectionUsage = usage, traceRecords = trace)
        Thread.sleep(5)
        cache.putStableContext("t2", "c2", 50)
        Thread.sleep(5)
        cache.putStableContext("t3", "c3", 60)

        assertNull(cache.getStableContext("t1"))
        assertNotNull(cache.getStableContext("t2"))
        assertNotNull(cache.getStableContext("t3"))

        cache.putStableContext("t3", "c3-updated", 123, sectionUsage = usage, traceRecords = trace)
        val hit = cache.getStableContext("t3")
        assertNotNull(hit)
        assertEquals(usage, hit.sectionUsage)
        assertEquals(trace, hit.traceRecords)
    }

    @Test
    fun `GlobalMetrics per-task cancellation does not affect sibling tasks or global flag`() {
        GlobalMetrics.resetCancellation()
        GlobalMetrics.removeAgent("task-a")
        GlobalMetrics.removeAgent("task-b")
        try {
            GlobalMetrics.requestCancellation("task-a")
            assertTrue(GlobalMetrics.isCancelled("task-a"))
            assertFalse(GlobalMetrics.isCancelled("task-b"))
            assertFalse(GlobalMetrics.isCancelled())

            GlobalMetrics.resetCancellation("task-a")
            assertFalse(GlobalMetrics.isCancelled("task-a"))
        } finally {
            GlobalMetrics.removeAgent("task-a")
            GlobalMetrics.removeAgent("task-b")
            GlobalMetrics.resetCancellation()
        }
    }

    @Test
    fun `ToolDescriptionBuilder compact parameter does not mutate shared compactMode flag`() {
        val dummyTool = object : Tool {
            override val name = "demo_tool"
            override val description = "First sentence. Second sentence with more detail."
            override val mode = ToolMode.READ_ONLY
            override val category = ToolCategory.FILE_PRODUCING
            override fun validateParams(params: Map<String, Any>) = Unit
            override suspend fun execute(params: Map<String, Any>) = ToolResult.success("ok")
            override fun getParameterSchema(): Map<String, Any> = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "File path"),
                    "opt" to mapOf("type" to "string", "description" to "Optional detail")
                ),
                "required" to listOf("path")
            )
        }
        val registry = ToolRegistry().apply { register(dummyTool) }
        val builder = ToolDescriptionBuilder(registry, compactMode = false)

        val compactDesc = builder.getToolDescriptions(TaskMode.PLAN, compact = true)
        val fullDesc = builder.getToolDescriptions(TaskMode.PLAN, compact = false)

        assertFalse(builder.compactMode)
        assertTrue(compactDesc.contains("Optional:"))
        assertTrue(fullDesc.contains("Example:"))
    }
}
