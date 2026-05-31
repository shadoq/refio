package pl.jclab.refio.core.services.turn

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals
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
        completionCondition: String? = null,
        runProfile: TurnRunProfile = TurnRunProfile.DEFAULT
    ) = GuardianContext(
        taskId = "task-1",
        mode = mode,
        runProfile = runProfile,
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
    fun `consults judge in PLAN mode (intent-without-action also bites in PLAN)`() = runBlocking {
        // Regression for the qwen3.5:9b loop: in PLAN the model emitted "Let me read the file…"
        // without any tool_call and the turn ended with no answer. PLAN drives a tool loop too,
        // so the intent-without-action stall must be pushed back once (single bounded re-entry).
        stubJudgeEnabled()
        stubModel()
        stubLlmResponse("""{"speaker": "model", "reason": "intent announced, no tool called"}""")

        val decision = guardian().check(
            ctx(
                mode = TaskMode.PLAN,
                response = "Let me read the files with explicit offset/limit parameters " +
                    "to get the complete content, starting with the TurnLoopConfig.kt file:",
                toolsUsed = listOf("read_file"),
                priorReentries = 0,
                toolsUsedSizeAtPriorReentry = 0
            )
        )

        // MODEL verdict at priorReentries=0 → single bounded re-entry with a focus nudge.
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
    fun `marks turn INCOMPLETE when prior re-entry produced no new tool call`() = runBlocking {
        // Reproduces the qwen3 / Ollama loop (sessions df4ba13c / 164d417d): the agent did 2 tool
        // calls earlier, the guardian re-entered once (snapshot=2), then the agent emitted
        // "Now let me find X" AGAIN with no new tool call (toolsUsed.size still 2). Nudging again
        // would just burn tokens — AND the request was never delivered, so the turn must finalize
        // as INCOMPLETE (previously it silently short-circuited to Pass → SUCCESS) without calling
        // the judge LLM.
        stubJudgeEnabled()
        val decision = guardian().check(
            ctx(
                response = "Now let me find the exact line numbers.",
                priorReentries = 1,
                toolsUsed = listOf("read_file", "grep_search"),
                toolsUsedSizeAtPriorReentry = 2
            )
        )
        assertTrue(decision is GuardianDecision.Incomplete)
        coVerify(exactly = 0) {
            llmClient.complete(provider = any(), model = any(), messages = any())
        }
    }

    @Test
    fun `marks turn INCOMPLETE when judge still says MODEL but re-entry budget is spent`() = runBlocking {
        // The agent DID call a new tool after the first nudge (snapshot=2, current=3) and reached
        // a fresh terminal point, but the judge STILL says the request is not delivered. The single
        // bounded re-entry is spent (priorReentries=1, gated on ==0), so we cannot nudge again — the
        // turn must finalize as INCOMPLETE rather than a silent Pass → SUCCESS.
        stubJudgeEnabled()
        stubModel()
        stubLlmResponse("""{"speaker": "model", "reason": "still not delivered"}""")

        val decision = guardian().check(
            ctx(
                response = "I have gathered the data; the analysis is still in progress.",
                priorReentries = 1,
                toolsUsed = listOf("read_file", "grep_search", "read_file"),
                toolsUsedSizeAtPriorReentry = 2
            )
        )

        assertTrue(decision is GuardianDecision.Incomplete)
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
        // MODEL verdict at priorReentries=0 → single bounded re-entry.
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
    fun `judge MODEL verdict produces single bounded re-entry`() = runBlocking {
        // MODEL verdict at priorReentries=0 → exactly one re-entry with a hard SYSTEM nudge.
        // (An interim 2026-05 revision made this observability-only / always-Pass, but
        // manual-tests showed qwen3.5:9b then silently abandoned multi-step tasks on bare
        // intent announcements, so the single bounded re-entry was restored.)
        stubJudgeEnabled()
        stubModel()
        stubLlmResponse("""{"speaker": "model", "reason": "agent announced next step but didn't act"}""")

        val decision = guardian().check(ctx(response = "I will now edit the bug fix into config.yaml."))

        assertTrue(decision is GuardianDecision.Reenter)
        val nudge = (decision as GuardianDecision.Reenter).nudge
        assertTrue(nudge.contains("NOT finished"), "nudge should name the failure mode")
    }

    @Test
    fun `DEFAULT-profile re-entry nudge tells the parent to write the file itself`() = runBlocking {
        // The parent (depth-0) orchestrator CAN write files and is responsible for persisting a
        // delegated subagent's analysis to disk — so its nudge keeps the file-deliverable steer.
        stubJudgeEnabled()
        stubModel()
        stubLlmResponse("""{"speaker": "model", "reason": "file deliverable not written yet"}""")

        val decision = guardian().check(
            ctx(
                response = "I'll now write the analysis to ./tmp/c9/analysis.md.",
                runProfile = TurnRunProfile.DEFAULT
            )
        )

        assertTrue(decision is GuardianDecision.Reenter)
        val nudge = (decision as GuardianDecision.Reenter).nudge
        assertTrue(
            nudge.contains("write the file yourself"),
            "parent nudge must keep the write-the-file steer"
        )
    }

    @Test
    fun `SUBAGENT-profile re-entry nudge does NOT tell a read-only subagent to write files`() = runBlocking {
        // Regression: the file-deliverable nudge ("you must then write the file yourself") fired
        // INSIDE a read-only subagent (business-analyst), which physically cannot write — it spun
        // re-reading the same file until loop-detection aborted it. A subagent's deliverable is the
        // COMPLETE text it returns to the caller, not a file it writes itself.
        stubJudgeEnabled()
        stubModel()
        stubLlmResponse("""{"speaker": "model", "reason": "analysis not yet delivered"}""")

        val decision = guardian().check(
            ctx(
                response = "Now I have all the data I need. Let me produce the final analysis.",
                runProfile = TurnRunProfile.SUBAGENT
            )
        )

        assertTrue(decision is GuardianDecision.Reenter)
        val nudge = (decision as GuardianDecision.Reenter).nudge
        assertTrue(
            !nudge.contains("write the file yourself"),
            "subagent nudge must NOT instruct a read-only subagent to write files"
        )
        assertTrue(
            nudge.contains("final text reply"),
            "subagent nudge should anchor on returning the complete result to the caller"
        )
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
        // Parse path is exercised: a fenced MODEL verdict still parses to MODEL, which at
        // priorReentries=0 yields a single bounded re-entry.
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
        // verified by the LLM judge against transcript evidence — this test exists to confirm
        // the judge LLM is actually consulted (the goal-mode pre-filter does not bypass it).
        // MODEL verdict at priorReentries=0 → single bounded re-entry; the judge LLM must run.
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
    fun `goal-aware MODEL verdict produces single bounded re-entry that re-injects the goal`() = runBlocking {
        // MODEL verdict in goal mode → single bounded re-entry whose nudge re-injects the
        // completion condition so the model re-anchors on the contract.
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
        assertTrue(
            (decision as GuardianDecision.Reenter).nudge.contains(goal),
            "goal-mode nudge should re-inject the completion condition"
        )
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
        // to minor wording tweaks. Whitespace is normalised first because the prompt wraps
        // the phrase across a line break ("…completion\ncondition…") — the discriminator is
        // the words, not their line layout.
        val sys = (systemSlot.captured ?: "").replace(Regex("\\s+"), " ")
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

        val sys = (systemSlot.captured ?: "").replace(Regex("\\s+"), " ")
        assertTrue(
            !sys.contains("user-defined completion condition", ignoreCase = true),
            "expected generic prompt, but goal-aware text leaked through: ${sys.take(120)}"
        )
    }
}
