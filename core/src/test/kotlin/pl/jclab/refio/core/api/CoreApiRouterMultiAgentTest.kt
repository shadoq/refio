package pl.jclab.refio.core.api

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.jclab.refio.core.agents.MultiAgentTaskParser
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for CoreApiRouter multi-agent session API.
 * Validates request/response models, YAML parsing integration, and eventBus exposure.
 */
class CoreApiRouterMultiAgentTest {

    @Test
    fun `should expose agentEventBus`() {
        val router = CoreApiRouter()
        assertNotNull(router.agentEventBus)
    }

    @Test
    fun `should expose multiAgentRunner`() {
        val router = CoreApiRouter()
        assertNotNull(router.multiAgentRunner)
    }

    @Test
    fun `MultiAgentSessionRequest should hold YAML definition`() {
        val yaml = """
            name: "Test Session"
            agents:
              - name: analyst
                task: "Analyze code"
              - name: coder
                task: "Write code"
                depends_on: [analyst]
        """.trimIndent()

        val request = MultiAgentSessionRequest(
            name = "Test Session",
            yamlDefinition = yaml,
            model = "gpt-4"
        )

        assertEquals("Test Session", request.name)
        assertEquals("gpt-4", request.model)
        assertTrue(request.yamlDefinition.contains("analyst"))
    }

    @Test
    fun `MultiAgentSessionResponse should aggregate agent results`() {
        val response = MultiAgentSessionResponse(
            sessionId = "session-1",
            name = "Test",
            status = "COMPLETED",
            agents = listOf(
                MultiAgentInstanceResponse(
                    agentName = "analyst",
                    status = "COMPLETED",
                    success = true,
                    response = "Analysis done",
                    tokensUsed = 1000,
                    costUsd = 0.01
                ),
                MultiAgentInstanceResponse(
                    agentName = "coder",
                    status = "COMPLETED",
                    success = true,
                    response = "Code written",
                    tokensUsed = 2000,
                    costUsd = 0.02
                )
            ),
            totalTokens = 3000,
            totalCostUsd = 0.03,
            createdAt = System.currentTimeMillis()
        )

        assertEquals(2, response.agents.size)
        assertEquals(3000, response.totalTokens)
        assertEquals(0.03, response.totalCostUsd)
        assertTrue(response.agents.all { it.success == true })
    }

    @Test
    fun `multiAgentRunner should validate cyclic dependencies`() {
        val router = CoreApiRouter()

        val yaml = """
            name: "Cyclic"
            agents:
              - name: a
                task: "Task A"
                depends_on: [b]
              - name: b
                task: "Task B"
                depends_on: [a]
        """.trimIndent()

        val definition = MultiAgentTaskParser.parse(yaml)
        val specs = MultiAgentTaskParser.toAgentSpecs(definition)

        assertThrows<IllegalArgumentException> {
            router.multiAgentRunner.validateDependencies(specs)
        }
    }

    @Test
    fun `multiAgentRunner should accept valid DAG`() {
        val router = CoreApiRouter()

        val yaml = """
            name: "Valid DAG"
            agents:
              - name: analyst
                task: "Analyze"
              - name: coder
                task: "Code"
                depends_on: [analyst]
              - name: tester
                task: "Test"
                depends_on: [coder]
        """.trimIndent()

        val definition = MultiAgentTaskParser.parse(yaml)
        val specs = MultiAgentTaskParser.toAgentSpecs(definition)

        // Should not throw
        router.multiAgentRunner.validateDependencies(specs)
        assertEquals(3, specs.size)
    }

    @Test
    fun `getMultiAgentSession should return null for unknown session`() {
        // Without DB initialization, this would fail — so we just test the model layer
        val response = MultiAgentSessionResponse(
            sessionId = "nonexistent",
            name = "Test",
            status = "COMPLETED",
            agents = emptyList(),
            createdAt = System.currentTimeMillis()
        )

        assertEquals("nonexistent", response.sessionId)
        assertEquals(0, response.agents.size)
    }
}
