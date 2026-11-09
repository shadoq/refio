package pl.jclab.refio.core.api.routers

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.agents.MultiAgentRunner
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.api.MultiAgentSessionRequest
import pl.jclab.refio.core.api.TaskResponse
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.AgentInstance
import pl.jclab.refio.core.db.AgentInstanceStatus
import pl.jclab.refio.core.db.AgentSession
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.AgentInstanceRepository
import pl.jclab.refio.core.db.repositories.AgentSessionRepository
import pl.jclab.refio.core.debug.TurnStopReason
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.subagents.models.SubagentDefinition
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a YAML workflow records and how each agent is set up: the session and agent statuses a
 * person reads afterwards, and the profile and limits each agent's turn runs with.
 */
class MultiAgentRouterTest {

    private val sessions = mockk<AgentSessionRepository>(relaxed = true)
    private val instances = mockk<AgentInstanceRepository>(relaxed = true)
    private val configService = mockk<ConfigService>(relaxed = true)
    private val turnRequests = CopyOnWriteArrayList<TurnRequest>()
    private var turnResult: (TurnRequest) -> TurnResult = { turn(success = true) }

    @BeforeEach
    fun setup() {
        every { sessions.create(any(), any(), any()) } returns
            AgentSession(id = "sess", projectId = "p", name = "w", status = "NEW", definitionYaml = null, createdAt = 0, completedAt = null)
        every { instances.create(any(), any(), any(), any(), any(), any(), any()) } answers {
            AgentInstance(
                id = "inst-${secondArg<String>()}", sessionId = "sess", taskId = null, name = secondArg(),
                profile = arg(3), status = "PENDING", model = null, taskDescription = thirdArg(), dependsOn = null,
                result = null, tokensIn = 0, tokensOut = 0, costUsd = 0.0, startedAt = null, completedAt = null, createdAt = 0,
            )
        }
    }

    private fun turn(success: Boolean, stopReason: TurnStopReason = TurnStopReason.UNKNOWN) = TurnResult(
        success = success, response = if (success) "done" else "stopped", iterations = 3,
        tokensIn = 10, tokensOut = 5, cost = 0.0, stopReason = stopReason,
    )

    private fun router(
        definitions: Map<String, SubagentDefinition> = emptyMap(),
        taskCost: (String) -> Double? = { null },
    ) = MultiAgentRouter(
        defaultProjectId = "p",
        defaultProjectPath = "/tmp/p",
        agentSessionRepository = sessions,
        agentInstanceRepository = instances,
        multiAgentRunner = MultiAgentRunner(AgentEventBus()),
        createTaskFn = { request ->
            TaskResponse(
                id = "task-${request.name.substringAfterLast(' ')}", name = request.name, mode = "AGENT", status = "NEW",
                readOnly = false, pinned = false, executionMode = "AUTO", uiState = null, createdAt = 0, updatedAt = 0,
            )
        },
        runTurnFn = { request, _ ->
            turnRequests += request
            turnResult(request)
        },
        configService = configService,
        subagentDefinitionFn = { definitions[it] },
        taskCostFn = taskCost,
    )

    @Test
    fun `session fails when any agent failed`() = runTest {
        // The session row said COMPLETED whatever its agents did, so a broken run looked finished in
        // every list, and an agent that stopped at its limit looked the same as one that delivered.
        turnResult = { request ->
            when (request.agentName) {
                "ok" -> turn(success = true)
                "partial" -> turn(success = false, stopReason = TurnStopReason.MAX_ITERATIONS)
                else -> turn(success = false, stopReason = TurnStopReason.TOOL_ERROR_RATE)
            }
        }
        val yaml = """
            name: w
            agents:
              - name: ok
                task: a
              - name: partial
                task: b
              - name: broken
                task: c
              - name: after
                task: d
                depends_on: [broken]
        """.trimIndent()

        val response = router().launchMultiAgentSession(MultiAgentSessionRequest(name = "w", yamlDefinition = yaml))

        assertEquals("FAILED", response.status)
        verify { sessions.updateStatus("sess", "FAILED", any()) }
        assertEquals(
            mapOf("ok" to "COMPLETED", "partial" to "INCOMPLETE", "broken" to "FAILED", "after" to "SKIPPED"),
            response.agents.associate { it.agentName to it.status },
        )
        verify { instances.updateStatus("inst-partial", AgentInstanceStatus.INCOMPLETE, any(), any()) }
        verify { instances.updateStatus("inst-broken", AgentInstanceStatus.FAILED, any(), any()) }
        verify { instances.updateStatus("inst-after", AgentInstanceStatus.SKIPPED, any(), any()) }
        assertTrue(turnRequests.none { it.agentName == "after" }, "the dependent of the broken agent never ran")
    }

    @Test
    fun `session is incomplete when an agent stopped at its limit`() = runTest {
        turnResult = { request -> if (request.agentName == "slow") turn(false, TurnStopReason.TIME_LIMIT) else turn(true) }
        val yaml = """
            name: w
            agents:
              - name: fast
                task: a
              - name: slow
                task: b
        """.trimIndent()

        val response = router().launchMultiAgentSession(MultiAgentSessionRequest(name = "w", yamlDefinition = yaml))

        assertEquals("INCOMPLETE", response.status)
        verify { sessions.updateStatus("sess", "INCOMPLETE", any()) }
    }

    @Test
    fun `profile runs the agent with its subagent definition and limits`() = runTest {
        // A profile used to be stored and shown, but the agent ran with the default instructions
        // and every tool.
        val reviewer = SubagentDefinition(
            name = "code-reviewer", description = "Reviews code", systemPrompt = "You review code.",
            allowedTools = listOf("read_file"), maxSteps = 15,
        )
        val yaml = """
            name: w
            max_cost: 3.0
            agents:
              - name: review
                profile: code-reviewer
                task: Review the change
                max_minutes: 20
              - name: plain
                task: Write docs
                max_iterations: 30
        """.trimIndent()

        router(mapOf("code-reviewer" to reviewer)).launchMultiAgentSession(MultiAgentSessionRequest(name = "w", yamlDefinition = yaml))

        val review = turnRequests.single { it.agentName == "review" }
        assertEquals(TurnRunProfile.SUBAGENT, review.runProfile)
        assertEquals("You review code.", review.profileOverrides?.systemPromptOverride)
        assertEquals(listOf("read_file"), review.profileOverrides?.allowedTools)
        assertEquals(15, review.profileOverrides?.maxIterationsOverride, "the profile's own step limit")
        val plain = turnRequests.single { it.agentName == "plain" }
        assertEquals(TurnRunProfile.DEFAULT, plain.runProfile)
        assertEquals(30, plain.profileOverrides?.maxIterationsOverride)
        verify { configService.setTyped(ConfigKeys.AGENT_MAX_TURN_MINUTES, 20L, ConfigScope.TASK, "task-review") }
        verify { configService.setTyped(ConfigKeys.AGENT_MAX_COST_USD, 3.0, ConfigScope.TASK, "task-review") }
    }

    @Test
    fun `unknown profile is rejected before anything runs`() = runTest {
        // A typo in a profile name would otherwise run the agent with the wrong instructions.
        val yaml = """
            name: w
            agents:
              - name: a
                profile: no-such-profile
                task: t
        """.trimIndent()

        val error = assertFailsWith<IllegalArgumentException> {
            router().launchMultiAgentSession(MultiAgentSessionRequest(name = "w", yamlDefinition = yaml))
        }

        assertTrue(error.message.orEmpty().contains("no-such-profile"), error.message)
        assertTrue(turnRequests.isEmpty())
        verify(exactly = 0) { sessions.create(any(), any(), any()) }
    }

    @Test
    fun `an agent's cost is what its task spent, subagents included`() = runTest {
        // The turn reports only its own model calls, while its subagents and delegated calls are
        // billed to the same task. The run's budget must be split on what was really spent.
        turnResult = { turn(success = true).copy(cost = 0.10) }
        val yaml = """
            name: w
            max_cost: 1.0
            agents:
              - name: lead
                task: a
              - name: follow
                task: b
                depends_on: [lead]
        """.trimIndent()

        val response = router(taskCost = { taskId -> if (taskId == "task-lead") 0.60 else 0.10 })
            .launchMultiAgentSession(MultiAgentSessionRequest(name = "w", yamlDefinition = yaml))

        assertEquals(0.60, response.agents.single { it.agentName == "lead" }.costUsd, 1e-9)
        val followBudget = slot<Double>()
        verify { configService.setTyped(ConfigKeys.AGENT_MAX_COST_USD, capture(followBudget), ConfigScope.TASK, "task-follow") }
        assertEquals(0.40, followBudget.captured, 1e-9, "what is left of the run's budget after the lead's real spending")
    }

    @Test
    fun `an agent whose turn throws keeps its task and what it spent`() = runTest {
        // An exception left the agent without its task id and with no cost: run.json lost the link
        // to its conversation and the run's budget counted nothing for it.
        turnResult = { throw IllegalStateException("model server unreachable") }
        val yaml = """
            name: w
            agents:
              - name: lead
                task: a
        """.trimIndent()

        val response = router(taskCost = { 0.25 }).launchMultiAgentSession(MultiAgentSessionRequest(name = "w", yamlDefinition = yaml))

        val lead = response.agents.single()
        assertEquals("FAILED", lead.status)
        assertEquals("task-lead", lead.taskId)
        assertEquals(0.25, lead.costUsd, 1e-9)
        assertTrue(lead.error.orEmpty().contains("model server unreachable"), lead.error)
    }
}
