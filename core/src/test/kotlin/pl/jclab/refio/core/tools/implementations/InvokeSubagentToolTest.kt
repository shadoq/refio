package pl.jclab.refio.core.tools.implementations

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.turn.TurnEventListener
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.subagents.models.SubagentDefinition
import pl.jclab.refio.core.subagents.models.SubagentInfo
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolCategory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Testy dla InvokeSubagentTool — narzędzia do wywoływania subagentów.
 */
class InvokeSubagentToolTest {

    private lateinit var mockSubagentRouterProvider: () -> SubagentRouter?
    private lateinit var mockSubagentRouter: SubagentRouter
    private lateinit var mockRunTurnCallback: suspend (Any, TurnEventListener?, StreamCallback?) -> TurnResult
    private lateinit var mockConfigServiceProvider: () -> ConfigService
    private lateinit var mockConfigService: ConfigService
    private lateinit var tool: InvokeSubagentTool

    private var runTurnCallCount = 0
    private var lastRunTurnRequest: Any? = null

    private val testAgentDefinition = SubagentDefinition(
        name = "test-agent",
        description = "Test agent",
        systemPrompt = "You are a test agent",
        enabled = true,
        allowedTools = null,
        disallowedTools = null,
        model = "default",
        priority = 0,
        maxSteps = 10
    )

    private val disabledAgentDefinition = SubagentDefinition(
        name = "disabled-agent",
        description = "Disabled agent",
        systemPrompt = "Disabled",
        enabled = false,
        allowedTools = null,
        disallowedTools = null,
        model = "default",
        priority = 0,
        maxSteps = 10
    )

    @BeforeEach
    fun setup() {
        mockSubagentRouter = mockk {
            coEvery { getSubagent(any()) } answers {
                val name = firstArg<String>()
                if (name == "test-agent") {
                    testAgentDefinition
                } else if (name == "disabled-agent") {
                    disabledAgentDefinition
                } else {
                    null
                }
            }
            coEvery { listSubagents(any()) } returns listOf(
                SubagentInfo(
                    name = testAgentDefinition.name,
                    description = testAgentDefinition.description,
                    tools = testAgentDefinition.allowedTools,
                    model = testAgentDefinition.model,
                    enabled = testAgentDefinition.enabled,
                    scope = "BUILTIN",
                    priority = testAgentDefinition.priority
                )
            )
        }

        mockSubagentRouterProvider = { mockSubagentRouter }

        mockConfigService = mockk {
            coEvery { getModel(any(), any()) } returns Pair("test-model", "test-provider")
        }
        mockConfigServiceProvider = { mockConfigService }

        runTurnCallCount = 0
        lastRunTurnRequest = null

        mockRunTurnCallback = { request, _, _ ->
            runTurnCallCount++
            lastRunTurnRequest = request
            TurnResult(
                success = true,
                response = "Subagent completed successfully",
                iterations = 1,
                tokensIn = 100,
                tokensOut = 50,
                cost = 0.005
            )
        }

        tool = InvokeSubagentTool(
            subagentRouterProvider = mockSubagentRouterProvider,
            runTurnCallback = mockRunTurnCallback,
            configServiceProvider = mockConfigServiceProvider
        )
    }

    @Nested
    inner class ToolMetadataTests {

        @Test
        fun `should have correct tool name`() {
            assertEquals("invoke_subagent", tool.name)
        }

        @Test
        fun `should have correct tool mode`() {
            assertEquals(ToolMode.READ_ONLY, tool.mode)
        }

        @Test
        fun `should have correct tool category`() {
            assertEquals(ToolCategory.DATA_PRODUCING, tool.category)
        }

        @Test
        fun `should have non-empty description`() {
            assertTrue(tool.description.isNotEmpty())
        }

        @Test
        fun `should include available subagents in description`() {
            // When
            val description = tool.description

            // Then
            assertTrue(description.contains("Subagent") || description.contains("subagent"))
            assertTrue(description.contains("test-agent"))
            assertTrue(description.contains("Test agent"))
        }
    }

    @Nested
    inner class SuccessfulInvocationTests {

        @Test
        fun `should invoke subagent successfully`() = runBlocking {
            // Given
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "test-agent",
                "goal" to "Review the code",
                "_mode" to "AGENT",
                "_execution_mode" to "AUTO"
            )

            // When
            val result = tool.execute(params)

            // Then
            assertTrue(result.success)
            assertEquals("Subagent completed successfully", result.output)
            assertEquals(1, runTurnCallCount)
        }

        @Test
        fun `should include metadata in result`() = runBlocking {
            // Given
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "test-agent",
                "goal" to "Test goal"
            )

            // When
            val result = tool.execute(params)

            // Then
            assertNotNull(result.metadata)
            assertEquals("test-agent", result.metadata!!["subagent_name"])
            assertEquals(1, result.metadata!!["depth"])
            assertEquals(1, result.metadata!!["iterations"])
            assertEquals(100, result.metadata!!["tokens_in"])
            assertEquals(50, result.metadata!!["tokens_out"])
        }

        @Test
        fun `should pass context refs to subagent`() = runBlocking {
            // Given
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "test-agent",
                "goal" to "Test",
                "context_refs" to listOf("file1.kt", "file2.kt")
            )

            // When
            val result = tool.execute(params)

            // Then
            assertTrue(result.success)
            assertEquals(1, runTurnCallCount)
        }

        @Test
        fun `should track depth in subagent chain`() = runBlocking {
            // Given
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "test-agent",
                "goal" to "Test",
                "_parent_depth" to 2
            )

            // When
            val result = tool.execute(params)

            // Then
            assertTrue(result.success)
            assertEquals(3, result.metadata!!["depth"])
        }

        @Test
        fun `should track subagent chain`() = runBlocking {
            // Given
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "test-agent",
                "goal" to "Test",
                "_subagent_chain" to listOf("parent-agent", "grandparent-agent")
            )

            // When
            val result = tool.execute(params)

            // Then
            assertTrue(result.success)
            assertEquals(1, runTurnCallCount)
        }

        @Test
        fun `should pass only ancestor chain to child turn`() = runBlocking {
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "test-agent",
                "goal" to "Test",
                "_subagent_chain" to listOf("parent-agent", "grandparent-agent")
            )

            val result = tool.execute(params)

            assertTrue(result.success)
            val request = lastRunTurnRequest as TurnRequest
            assertEquals(listOf("parent-agent", "grandparent-agent"), request.profileOverrides?.subagentChain)
            assertEquals("test-agent", request.profileOverrides?.subagentName)
        }
    }

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `should return error when subagent_name is missing`() = runBlocking {
            // Given
            val params = mapOf(
                "_task_id" to "task-123",
                "goal" to "Test"
            )

            // When
            val result = tool.execute(params)

            // Then
            assertFalse(result.success)
            assertTrue(result.error!!.contains("subagent_name", ignoreCase = true))
        }

        @Test
        fun `should return error when goal is missing`() = runBlocking {
            // Given
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "test-agent"
            )

            // When
            val result = tool.execute(params)

            // Then
            assertFalse(result.success)
            assertTrue(result.error!!.contains("goal", ignoreCase = true))
        }

        @Test
        fun `should return error when goal is empty`() = runBlocking {
            // Given
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "test-agent",
                "goal" to "   "
            )

            // When
            val result = tool.execute(params)

            // Then
            assertFalse(result.success)
            assertTrue(result.error!!.contains("empty", ignoreCase = true))
        }

        @Test
        fun `should return error when subagent not found`() = runBlocking {
            // Given
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "nonexistent-agent",
                "goal" to "Test"
            )

            // When
            val result = tool.execute(params)

            // Then
            assertFalse(result.success)
            assertTrue(result.error!!.contains("not found", ignoreCase = true))
        }

        @Test
        fun `should return error when subagent is disabled`() = runBlocking {
            // Given
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "disabled-agent",
                "goal" to "Test"
            )

            // When
            val result = tool.execute(params)

            // Then
            assertFalse(result.success)
            assertTrue(result.error!!.contains("disabled", ignoreCase = true))
        }

        @Test
        fun `should return error when _task_id is missing`() = runBlocking {
            // Given
            val params = mapOf(
                "subagent_name" to "test-agent",
                "goal" to "Test"
            )

            // When
            val result = tool.execute(params)

            // Then
            assertFalse(result.success)
            assertTrue(result.error!!.contains("task_id", ignoreCase = true))
        }

        @Test
        fun `should return error when subagent system not available`() = runBlocking {
            // Given
            val toolWithoutRouter = InvokeSubagentTool(
                subagentRouterProvider = { null },
                runTurnCallback = mockRunTurnCallback,
                configServiceProvider = mockConfigServiceProvider
            )

            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "test-agent",
                "goal" to "Test"
            )

            // When
            val result = toolWithoutRouter.execute(params)

            // Then
            assertFalse(result.success)
            assertTrue(result.error!!.contains("not available", ignoreCase = true))
        }

        @Test
        fun `should return error when subagent execution fails`() = runBlocking {
            // Given
            val failingCallback: suspend (Any, TurnEventListener?, StreamCallback?) -> TurnResult = { _, _, _ ->
                TurnResult(
                    success = false,
                    response = "Execution failed",
                    iterations = 0,
                    tokensIn = 0,
                    tokensOut = 0,
                    cost = 0.0
                )
            }

            val failingTool = InvokeSubagentTool(
                subagentRouterProvider = mockSubagentRouterProvider,
                runTurnCallback = failingCallback,
                configServiceProvider = mockConfigServiceProvider
            )

            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "test-agent",
                "goal" to "Test"
            )

            // When
            val result = failingTool.execute(params)

            // Then
            assertFalse(result.success)
            assertTrue(result.error!!.contains("failed", ignoreCase = true))
        }
    }

    @Nested
    inner class RecursionDetectionTests {

        @Test
        fun `should detect direct recursion`() = runBlocking {
            // Given
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "test-agent",
                "goal" to "Test",
                "_subagent_chain" to listOf("test-agent")  // Already in chain
            )

            // When
            val result = tool.execute(params)

            // Then
            assertFalse(result.success)
            assertTrue(result.error!!.contains("recursion", ignoreCase = true))
        }

        @Test
        fun `should detect case-insensitive recursion`() = runBlocking {
            // Given
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "Test-Agent",  // Different case
                "goal" to "Test",
                "_subagent_chain" to listOf("test-agent")
            )

            // When
            val result = tool.execute(params)

            // Then
            assertFalse(result.success)
            assertTrue(result.error!!.contains("recursion", ignoreCase = true))
        }

        @Test
        fun `should allow different subagent in chain`() = runBlocking {
            // Given
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "different-agent",
                "goal" to "Test",
                "_subagent_chain" to listOf("parent-agent")
            )

            // When
            val result = tool.execute(params)

            // Then
            // Should fail because "different-agent" doesn't exist, not because of recursion
            assertFalse(result.success)
            assertTrue(result.error!!.contains("not found", ignoreCase = true))
        }
    }

    @Nested
    inner class ParameterSchemaTests {

        @Test
        fun `should return valid parameter schema`() {
            // When
            val schema = tool.getParameterSchema()

            // Then
            assertEquals("object", schema["type"])
            val properties = schema["properties"] as Map<*, *>
            assertNotNull(properties["subagent_name"])
            assertNotNull(properties["goal"])
            assertNotNull(properties["context_refs"])

            val required = schema["required"] as List<*>
            assertTrue(required.contains("subagent_name"))
            assertTrue(required.contains("goal"))
        }
    }

    @Nested
    inner class SecurityCeilingTests {

        @Test
        fun `should default to PLAN mode when _mode not specified`() = runBlocking {
            // Security ceiling: missing _mode defaults to PLAN (safe fallback), not AGENT
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "test-agent",
                "goal" to "Test"
                // No _mode parameter
            )

            val result = tool.execute(params)
            assertTrue(result.success)

            val request = lastRunTurnRequest as TurnRequest
            assertEquals(pl.jclab.refio.core.db.TaskMode.PLAN, request.mode)
        }

        @Test
        fun `should inherit PLAN mode from parent`() = runBlocking {
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "test-agent",
                "goal" to "Test",
                "_mode" to "PLAN"
            )

            val result = tool.execute(params)
            assertTrue(result.success)

            val request = lastRunTurnRequest as TurnRequest
            assertEquals(pl.jclab.refio.core.db.TaskMode.PLAN, request.mode)
        }

        @Test
        fun `should inherit AGENT mode from parent`() = runBlocking {
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "test-agent",
                "goal" to "Test",
                "_mode" to "AGENT"
            )

            val result = tool.execute(params)
            assertTrue(result.success)

            val request = lastRunTurnRequest as TurnRequest
            assertEquals(pl.jclab.refio.core.db.TaskMode.AGENT, request.mode)
        }
    }

    @Nested
    inner class DefaultParameterTests {

        @Test
        fun `should default to PLAN mode when not specified`() = runBlocking {
            // Given
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "test-agent",
                "goal" to "Test"
                // No _mode parameter
            )

            // When
            val result = tool.execute(params)

            // Then
            assertTrue(result.success)
        }

        @Test
        fun `should default to AUTO execution mode when not specified`() = runBlocking {
            // Given
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "test-agent",
                "goal" to "Test"
                // No _execution_mode parameter
            )

            // When
            val result = tool.execute(params)

            // Then
            assertTrue(result.success)
        }

        @Test
        fun `should default depth to 0 when not specified`() = runBlocking {
            // Given
            val params = mapOf(
                "_task_id" to "task-123",
                "subagent_name" to "test-agent",
                "goal" to "Test"
            )

            // When
            val result = tool.execute(params)

            // Then
            assertTrue(result.success)
            assertEquals(1, result.metadata!!["depth"])  // 0 + 1
        }
    }
}
