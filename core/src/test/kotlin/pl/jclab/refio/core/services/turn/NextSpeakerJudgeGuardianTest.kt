package pl.jclab.refio.core.services.turn

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.services.ConfigService

class NextSpeakerJudgeGuardianTest {

    private val llmClient = mockk<LLMClient>()
    private val configService = mockk<ConfigService>(relaxed = true)

    private fun guardian() = NextSpeakerJudgeGuardian(llmClient, configService)

    private fun ctx(
        mode: TaskMode = TaskMode.AGENT,
        response: String = "I checked the configuration file.",
        priorReentries: Int = 0,
        toolsUsed: List<String> = listOf("read_file"),
        toolsUsedSizeAtPriorReentry: Int = 0,
        completionCondition: String? = null
    ) = GuardianContext(
        taskId = "task-1",
        mode = mode,
        runProfile = TurnRunProfile.DEFAULT,
        iteration = 3,
        maxIterations = 50,
        userRequest = "Fix the bug in config parsing",
        finalResponse = response,
        toolsUsed = toolsUsed,
        writeToolsExecutedInTurn = 0,
        verificationToolsExecutedAfterWrite = 0,
        priorReentries = priorReentries,
        toolsUsedSizeAtPriorReentry = toolsUsedSizeAtPriorReentry,
        completionCondition = completionCondition
    )

    private fun stubJudgeEnabled(enabled: Boolean = true) {
        every {
            configService.getTyped(ConfigKeys.GENERAL_NEXT_SPEAKER_JUDGE_ENABLED, "task-1")
        } returns enabled
    }

    private fun stubModel() {
        every { configService.getModel(ModelOperation.WEAK, "task-1") } returns ("haiku" to "anthropic")
    }

    private fun stubLlmResponse(content: String) {
        coEvery {
            llmClient.complete(
                provider = any(),
                model = any(),
                messages = any(),
                systemPrompt = any(),
                maxTokens = any(),
                temperature = any(),
                responseFormat = any(),
                thinking = any(),
                reasoningEffort = any(),
                noEgressEnabled = any(),
                stream = any(),
                onChunk = any(),
                taskId = any(),
                subtaskId = any(),
                source = any(),
                contextContent = any(),
                systemMessages = any(),
                kwargs = any()
            )
        } returns LLMResponse(
            content = content,
            usage = LLMUsage(150, 20, 170),
            cost = 0.0001,
            model = "haiku",
            provider = "anthropic"
        )
    }

    @Test
    fun `passes immediately in CHAT mode without calling LLM`() = runBlocking {
        val decision = guardian().check(ctx(mode = TaskMode.CHAT))
        assertEquals(GuardianDecision.Pass, decision)
        coVerify(exactly = 0) {
            llmClient.complete(provider = any(), model = any(), messages = any())
        }
    }

    @Test
    fun `passes immediately in PLAN mode without calling LLM`() = runBlocking {
        val decision = guardian().check(ctx(mode = TaskMode.PLAN))
        assertEquals(GuardianDecision.Pass, decision)
        coVerify(exactly = 0) {
            llmClient.complete(provider = any(), model = any(), messages = any())
        }
    }

    @Test
    fun `passes when judge is disabled in config`() = runBlocking {
        stubJudgeEnabled(false)
        val decision = guardian().check(ctx())
        assertEquals(GuardianDecision.Pass, decision)
        coVerify(exactly = 0) {
            llmClient.complete(provider = any(), model = any(), messages = any())
        }
    }

    @Test
    fun `passes when priorReentries hits the per-turn cap`() = runBlocking {
        stubJudgeEnabled()
        val decision = guardian().check(
            ctx(priorReentries = NextSpeakerJudgeGuardian.MAX_JUDGE_REENTRIES)
        )
        assertEquals(GuardianDecision.Pass, decision)
        coVerify(exactly = 0) {
            llmClient.complete(provider = any(), model = any(), messages = any())
        }
    }

    @Test
    fun `passes immediately when prior re-entry produced no new tool call`() = runBlocking {
        // Reproduces the qwen3 / Ollama loop: agent did 2 tool calls earlier in the turn,
        // guardian re-entered once (snapshot=2), agent then emitted "Let me find X" again
        // without calling any new tool (toolsUsed.size is still 2). Nudging again would
        // just burn tokens — short-circuit to Pass without calling the judge LLM.
        stubJudgeEnabled()
        val decision = guardian().check(
            ctx(
                response = "Now let me find the exact line numbers.",
                priorReentries = 1,
                toolsUsed = listOf("read_file", "grep_search"),
                toolsUsedSizeAtPriorReentry = 2
            )
        )
        assertEquals(GuardianDecision.Pass, decision)
        coVerify(exactly = 0) {
            llmClient.complete(provider = any(), model = any(), messages = any())
        }
    }

    @Test
    fun `still consults judge when prior re-entry yielded a new tool call`() = runBlocking {
        // After re-entry the agent DID call a new tool (snapshot=2, current=3). That means
        // the nudge worked — we have no reason to short-circuit, judge runs normally.
        stubJudgeEnabled()
        stubModel()
        stubLlmResponse("""{"speaker": "user", "reason": "agent recovered and delivered answer"}""")

        val decision = guardian().check(
            ctx(
                response = "Done. PLAN: 100 iters, AGENT: 100 iters.",
                priorReentries = 1,
                toolsUsed = listOf("read_file", "grep_search", "read_file"),
                toolsUsedSizeAtPriorReentry = 2
            )
        )

        assertEquals(GuardianDecision.Pass, decision)
        coVerify(exactly = 1) {
            llmClient.complete(
                provider = any(), model = any(), messages = any(), systemPrompt = any(),
                maxTokens = any(), temperature = any(), responseFormat = any(),
                thinking = any(), reasoningEffort = any(), noEgressEnabled = any(),
                stream = any(), onChunk = any(), taskId = any(), subtaskId = any(),
                source = any(), contextContent = any(), systemMessages = any(), kwargs = any()
            )
        }
    }

    @Test
    fun `first re-entry (no snapshot yet) still consults judge`() = runBlocking {
        // Defense in depth: priorReentries=0 means snapshot is still 0; the short-circuit
        // must not fire on the very first guardian invocation regardless of toolsUsed.
        // Response is intentionally >30 chars to bypass the length-based pre-filter.
        stubJudgeEnabled()
        stubModel()
        stubLlmResponse("""{"speaker": "model", "reason": "agent paused"}""")

        val decision = guardian().check(
            ctx(
                response = "I will now run the full test suite to verify the migration works correctly.",
                priorReentries = 0,
                toolsUsed = emptyList(),
                toolsUsedSizeAtPriorReentry = 0
            )
        )

        assertTrue(decision is GuardianDecision.Reenter)
    }

    @Test
    fun `pre-filter passes on trailing question mark without LLM call`() = runBlocking {
        stubJudgeEnabled()
        val decision = guardian().check(
            ctx(response = "Which file should I edit — config.yaml or settings.json?")
        )
        assertEquals(GuardianDecision.Pass, decision)
        coVerify(exactly = 0) {
            llmClient.complete(provider = any(), model = any(), messages = any())
        }
    }

    @Test
    fun `pre-filter passes on explicit completion marker without LLM call`() = runBlocking {
        stubJudgeEnabled()
        val decision = guardian().check(
            ctx(response = "Refactor applied across all three files. Task complete.")
        )
        assertEquals(GuardianDecision.Pass, decision)
        coVerify(exactly = 0) {
            llmClient.complete(provider = any(), model = any(), messages = any())
        }
    }

    @Test
    fun `pre-filter passes on short response without LLM call`() = runBlocking {
        stubJudgeEnabled()
        val decision = guardian().check(ctx(response = "Done."))
        assertEquals(GuardianDecision.Pass, decision)
        coVerify(exactly = 0) {
            llmClient.complete(provider = any(), model = any(), messages = any())
        }
    }

    @Test
    fun `judge USER verdict produces Pass`() = runBlocking {
        stubJudgeEnabled()
        stubModel()
        stubLlmResponse("""{"speaker": "user", "reason": "agent delivered a final summary"}""")

        val decision = guardian().check(ctx())

        assertEquals(GuardianDecision.Pass, decision)
    }

    @Test
    fun `judge MODEL verdict produces Reenter with nudge`() = runBlocking {
        stubJudgeEnabled()
        stubModel()
        stubLlmResponse("""{"speaker": "model", "reason": "agent announced next step but didn't act"}""")

        val decision = guardian().check(ctx(response = "I will now edit the bug fix into config.yaml."))

        assertTrue(decision is GuardianDecision.Reenter, "expected Reenter, got $decision")
        assertTrue(decision.nudge.isNotBlank(), "nudge should not be blank")
        assertTrue(decision.reason.contains("judge"))
    }

    @Test
    fun `judge response wrapped in markdown fence still parses`() = runBlocking {
        stubJudgeEnabled()
        stubModel()
        stubLlmResponse(
            """
            ```json
            {"speaker": "model", "reason": "paused"}
            ```
            """.trimIndent()
        )

        val decision = guardian().check(ctx(response = "Now I will run the tests next."))
        assertTrue(decision is GuardianDecision.Reenter)
    }

    @Test
    fun `judge response with leading prose still extracts JSON object`() = runBlocking {
        stubJudgeEnabled()
        stubModel()
        stubLlmResponse("""Here is my decision: {"speaker": "user"} hope this helps.""")

        val decision = guardian().check(ctx())
        assertEquals(GuardianDecision.Pass, decision)
    }

    @Test
    fun `malformed JSON treated as UNCERTAIN and produces Pass`() = runBlocking {
        stubJudgeEnabled()
        stubModel()
        stubLlmResponse("this is not json at all")

        val decision = guardian().check(ctx())
        assertEquals(GuardianDecision.Pass, decision)
    }

    @Test
    fun `unknown speaker value treated as UNCERTAIN and produces Pass`() = runBlocking {
        stubJudgeEnabled()
        stubModel()
        stubLlmResponse("""{"speaker": "robot", "reason": "weird"}""")

        val decision = guardian().check(ctx())
        assertEquals(GuardianDecision.Pass, decision)
    }

    @Test
    fun `LLM exception is swallowed and produces Pass`() = runBlocking {
        stubJudgeEnabled()
        stubModel()
        coEvery {
            llmClient.complete(
                provider = any(), model = any(), messages = any(), systemPrompt = any(),
                maxTokens = any(), temperature = any(), responseFormat = any(),
                thinking = any(), reasoningEffort = any(), noEgressEnabled = any(),
                stream = any(), onChunk = any(), taskId = any(), subtaskId = any(),
                source = any(), contextContent = any(), systemMessages = any(),
                kwargs = any()
            )
        } throws RuntimeException("network down")

        val decision = guardian().check(ctx())
        assertEquals(GuardianDecision.Pass, decision)
    }

    @Test
    fun `empty final response passes without LLM call`() = runBlocking {
        stubJudgeEnabled()
        val decision = guardian().check(ctx(response = ""))
        assertEquals(GuardianDecision.Pass, decision)
        coVerify(exactly = 0) {
            llmClient.complete(provider = any(), model = any(), messages = any())
        }
    }

    @Test
    fun `judge uses WEAK model and NextSpeakerJudge source for billing`() = runBlocking {
        stubJudgeEnabled()
        stubModel()
        stubLlmResponse("""{"speaker": "user"}""")

        val sourceSlot = slot<String>()
        coEvery {
            llmClient.complete(
                provider = any(), model = any(), messages = any(), systemPrompt = any(),
                maxTokens = any(), temperature = any(), responseFormat = any(),
                thinking = any(), reasoningEffort = any(), noEgressEnabled = any(),
                stream = any(), onChunk = any(), taskId = any(), subtaskId = any(),
                source = capture(sourceSlot), contextContent = any(), systemMessages = any(),
                kwargs = any()
            )
        } returns LLMResponse(
            content = """{"speaker": "user"}""",
            usage = LLMUsage(1, 1, 2),
            model = "haiku",
            provider = "anthropic",
            cost = 0.0
        )

        guardian().check(ctx())

        coVerify { configService.getModel(ModelOperation.WEAK, "task-1") }
        assertEquals("NextSpeakerJudge", sourceSlot.captured)
    }

    // ===== Goal-aware mode (`/goal`) =====

    @Test
    fun `goal-aware mode does NOT short-circuit on textual completion markers`() = runBlocking {
        // In generic mode "Refactor applied. Task complete." short-circuits via the pre-filter
        // because the heuristic trusts the textual claim. In goal mode the same claim must be
        // verified by the LLM judge against transcript evidence.
        stubJudgeEnabled()
        stubModel()
        stubLlmResponse("""{"speaker": "model", "reason": "no test run in transcript"}""")

        val decision = guardian().check(
            ctx(
                response = "Refactor applied to all five files. Task complete.",
                completionCondition = "all tests in src/test pass"
            )
        )

        assertTrue(decision is GuardianDecision.Reenter)
        coVerify(exactly = 1) {
            llmClient.complete(
                provider = any(), model = any(), messages = any(), systemPrompt = any(),
                maxTokens = any(), temperature = any(), responseFormat = any(),
                thinking = any(), reasoningEffort = any(), noEgressEnabled = any(),
                stream = any(), onChunk = any(), taskId = any(), subtaskId = any(),
                source = any(), contextContent = any(), systemMessages = any(), kwargs = any()
            )
        }
    }

    @Test
    fun `goal-aware mode still short-circuits on trailing question mark`() = runBlocking {
        // Clarifying questions always mean "user takes over", regardless of an active goal.
        stubJudgeEnabled()
        val decision = guardian().check(
            ctx(
                response = "Which test framework should I target — pytest or unittest?",
                completionCondition = "all tests pass"
            )
        )
        assertEquals(GuardianDecision.Pass, decision)
        coVerify(exactly = 0) {
            llmClient.complete(provider = any(), model = any(), messages = any())
        }
    }

    @Test
    fun `goal-aware judge USER verdict produces Pass`() = runBlocking {
        stubJudgeEnabled()
        stubModel()
        stubLlmResponse("""{"speaker": "user", "reason": "transcript shows pytest output with 47 passed"}""")

        val decision = guardian().check(
            ctx(
                response = "Tests all pass — pytest reported 47 passed, 0 failed.",
                completionCondition = "all tests in src/test pass"
            )
        )

        assertEquals(GuardianDecision.Pass, decision)
    }

    @Test
    fun `goal-aware MODEL verdict Reenter nudge includes the goal text`() = runBlocking {
        stubJudgeEnabled()
        stubModel()
        stubLlmResponse("""{"speaker": "model", "reason": "agent edited files but never ran tests"}""")

        val goal = "all tests in src/test pass and migration runs cleanly"
        val decision = guardian().check(
            ctx(
                response = "Migrated 5 files. The migration should work now.",
                completionCondition = goal
            )
        )

        assertTrue(decision is GuardianDecision.Reenter)
        assertTrue(decision.nudge.contains(goal), "nudge should re-inject the goal text, was: ${decision.nudge}")
        assertEquals("judge: goal not yet met", decision.reason)
    }

    @Test
    fun `goal-aware system prompt is used when condition is present`() = runBlocking {
        stubJudgeEnabled()
        stubModel()
        val systemSlot = slot<String>()
        coEvery {
            llmClient.complete(
                provider = any(), model = any(), messages = any(), systemPrompt = capture(systemSlot),
                maxTokens = any(), temperature = any(), responseFormat = any(),
                thinking = any(), reasoningEffort = any(), noEgressEnabled = any(),
                stream = any(), onChunk = any(), taskId = any(), subtaskId = any(),
                source = any(), contextContent = any(), systemMessages = any(), kwargs = any()
            )
        } returns LLMResponse(
            content = """{"speaker": "user"}""",
            usage = LLMUsage(1, 1, 2),
            model = "haiku",
            provider = "anthropic",
            cost = 0.0
        )

        guardian().check(ctx(completionCondition = "all tests pass"))

        // The goal-aware system prompt mentions "user-defined completion condition"; the
        // generic prompt does not. Distinguishing on a stable phrase keeps the test robust
        // to minor wording tweaks elsewhere in either prompt.
        val sys = systemSlot.captured ?: ""
        assertTrue(
            sys.contains("user-defined completion condition", ignoreCase = true),
            "expected goal-aware prompt, got: ${sys.take(120)}"
        )
    }

    @Test
    fun `generic prompt is still used when no condition is set`() = runBlocking {
        stubJudgeEnabled()
        stubModel()
        val systemSlot = slot<String>()
        coEvery {
            llmClient.complete(
                provider = any(), model = any(), messages = any(), systemPrompt = capture(systemSlot),
                maxTokens = any(), temperature = any(), responseFormat = any(),
                thinking = any(), reasoningEffort = any(), noEgressEnabled = any(),
                stream = any(), onChunk = any(), taskId = any(), subtaskId = any(),
                source = any(), contextContent = any(), systemMessages = any(), kwargs = any()
            )
        } returns LLMResponse(
            content = """{"speaker": "user"}""",
            usage = LLMUsage(1, 1, 2),
            model = "haiku",
            provider = "anthropic",
            cost = 0.0
        )

        guardian().check(ctx())  // no completionCondition

        val sys = systemSlot.captured ?: ""
        assertTrue(
            !sys.contains("user-defined completion condition", ignoreCase = true),
            "expected generic prompt, but goal-aware text leaked through: ${sys.take(120)}"
        )
    }
}
