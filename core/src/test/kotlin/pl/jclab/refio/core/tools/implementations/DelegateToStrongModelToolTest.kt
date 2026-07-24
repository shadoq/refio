package pl.jclab.refio.core.tools.implementations

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.turn.TurnEventListener
import pl.jclab.refio.core.services.turn.TurnSubagentValidator
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for `delegate_to_strong_model` in tool-enabled mode.
 *
 * The bug (observed 2026-07): the very first `allow_tools=true` delegation from ANY agent aborted
 * with `Strong model (tool-enabled) error: Subagent recursion detected for 'strong-model'`. The tool
 * built the child request with `subagentChain = parentChain + "strong-model"` — pre-adding its OWN
 * name — while [TurnSubagentValidator] treats `subagentChain` as the ANCESTOR chain and checks the
 * child's own name against it. So `strong-model` always found itself and tripped a false recursion.
 *
 * The contract these tests pin: the child request must carry ancestors ONLY. The current agent's name
 * is added to the chain by the turn executor when a further tool is spawned, exactly as
 * `invoke_subagent` relies on. Genuine strong-model→strong-model recursion is still caught because
 * the ancestor chain will already contain "strong-model" by then.
 */
class DelegateToStrongModelToolTest {

    private lateinit var tool: DelegateToStrongModelTool
    private var capturedRequest: TurnRequest? = null

    private val validator = TurnSubagentValidator() // real validator = the production recursion gate

    @BeforeEach
    fun setup() {
        capturedRequest = null
        val llmClient = mockk<LLMClient>(relaxed = true) // unused on the tool-enabled path
        val configService = mockk<ConfigService> {
            every { getStrongModel(any(), any()) } returns Pair("strong-model-id", "openrouter")
        }
        val runTurnCallback: suspend (TurnRequest, TurnEventListener?, StreamCallback?) -> TurnResult =
            { request, _, _ ->
                capturedRequest = request
                TurnResult(
                    success = true,
                    response = "strong model done",
                    iterations = 3,
                    tokensIn = 100,
                    tokensOut = 50,
                    cost = 0.01
                )
            }
        tool = DelegateToStrongModelTool(
            llmClient = llmClient,
            configServiceProvider = { configService },
            runTurnCallback = runTurnCallback
        )
    }

    @Test
    fun `top-level tool-enabled delegation does not pre-add itself to the subagent chain`() = runBlocking {
        val result = tool.execute(
            mapOf(
                "_task_id" to "task-1",
                "task" to "analyze the architecture",
                "allow_tools" to true,
                "_mode" to "AGENT",
                "_parent_depth" to 0,
                "_subagent_chain" to emptyList<String>()
            )
        )

        assertTrue(result.success, "delegation must succeed, not fail on a false recursion: ${result.error}")
        val overrides = capturedRequest?.profileOverrides
        assertEquals(
            emptyList<String>(), overrides?.subagentChain,
            "child chain must be ancestors-only (empty for a top-level call), NOT [strong-model]"
        )
        assertEquals("strong-model", overrides?.subagentName)
    }

    @Test
    fun `the built request passes the production recursion validator`() = runBlocking {
        // This is the exact end-to-end reproduction: build the child request via the tool, then feed
        // it through the SAME validator the turn loop runs. Before the fix this threw; now it must not.
        tool.execute(
            mapOf(
                "_task_id" to "task-1",
                "task" to "do the thing",
                "allow_tools" to true,
                "_mode" to "AGENT",
                "_subagent_chain" to emptyList<String>()
            )
        )
        val request = capturedRequest!!
        // Should NOT throw — a top-level strong-model delegation is not a recursion.
        validator.validateRecursion(request.runProfile, request.profileOverrides)
        assertEquals(TurnRunProfile.SUBAGENT, request.runProfile)
    }

    @Test
    fun `a non-strong ancestor chain is preserved unchanged and still validates`() = runBlocking {
        tool.execute(
            mapOf(
                "_task_id" to "task-1",
                "task" to "do the thing",
                "allow_tools" to true,
                "_mode" to "AGENT",
                "_subagent_chain" to listOf("architect-reviewer")
            )
        )
        val overrides = capturedRequest?.profileOverrides
        assertEquals(
            listOf("architect-reviewer"), overrides?.subagentChain,
            "ancestors must be passed through verbatim, without appending self"
        )
        // strong-model is not among the ancestors → no recursion.
        validator.validateRecursion(capturedRequest!!.runProfile, overrides)
    }

    @Test
    fun `genuine strong-model self-recursion is still caught`() = runBlocking {
        // When strong-model itself delegates to strong-model, the turn executor will have already
        // placed "strong-model" into the ancestor chain. The tool must pass that through so the
        // validator can still refuse the real recursion — the fix must not disable the guard.
        tool.execute(
            mapOf(
                "_task_id" to "task-1",
                "task" to "recurse into myself",
                "allow_tools" to true,
                "_mode" to "AGENT",
                "_subagent_chain" to listOf("strong-model")
            )
        )
        val request = capturedRequest!!
        assertEquals(listOf("strong-model"), request.profileOverrides?.subagentChain)
        val ex = assertThrows<IllegalArgumentException> {
            validator.validateRecursion(request.runProfile, request.profileOverrides)
        }
        assertTrue(ex.message!!.contains("recursion", ignoreCase = true))
    }
}
