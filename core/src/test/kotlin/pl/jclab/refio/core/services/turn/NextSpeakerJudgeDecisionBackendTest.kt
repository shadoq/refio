package pl.jclab.refio.core.services.turn

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.decision.DecisionCallException
import pl.jclab.refio.core.llm.decision.DecisionClient
import pl.jclab.refio.core.llm.decision.DecisionResult
import pl.jclab.refio.core.llm.decision.YesNoQuestion
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.turn.DecisionNextSpeakerJudge.Step

/**
 * The completion judge backed by a decision model: which judge answers for each setting, and that
 * the LLM judge still answers whenever the decision model cannot, so switching the setting never
 * removes the safety net.
 */
class NextSpeakerJudgeDecisionBackendTest {

    private val llmClient = mockk<LLMClient>()
    private val configService = mockk<ConfigService>(relaxed = true)

    /** Records what the guardian asked and answers with fixed probabilities (or fails). */
    private class FakeDecisionClient(
        var answers: Map<String, Double> = emptyMap(),
        var failure: Exception? = null
    ) : DecisionClient {
        val calls = mutableListOf<Triple<String, String, Set<String>>>()

        override suspend fun askYesNo(
            model: String,
            state: String,
            questions: Map<String, YesNoQuestion>,
            taskId: String?,
            source: String
        ): DecisionResult {
            calls += Triple(model, state, questions.keys)
            failure?.let { throw it }
            return DecisionResult(questions.keys.associateWith { answers[it] ?: 0.0 }, inputTokens = 900, latencyMs = 950)
        }
    }

    private val decisionClient = FakeDecisionClient()
    private val providersAsked = mutableListOf<String>()

    private fun guardian() = NextSpeakerJudgeGuardian(
        llmClient,
        configService,
        DecisionNextSpeakerJudge { provider ->
            providersAsked += provider
            decisionClient
        }
    )

    private fun ctx(
        mode: TaskMode = TaskMode.AGENT,
        response: String = "I fixed the parser and the project compiles cleanly now.",
        toolsUsed: List<String> = listOf("read_file", "code_editing"),
        completionCondition: String? = null
    ) = GuardianContext(
        taskId = "task-1",
        mode = mode,
        runProfile = TurnRunProfile.DEFAULT,
        iteration = 3,
        maxIterations = 50,
        userRequest = "Fix the bug in config parsing and make sure it compiles",
        finalResponse = response,
        toolsUsed = toolsUsed,
        writeToolsExecutedInTurn = 0,
        fileWriteToolsExecutedInTurn = 0,
        verificationToolsExecutedAfterWrite = 0,
        priorReentries = 0,
        toolsUsedSizeAtPriorReentry = 0,
        completionCondition = completionCondition
    )

    private fun stubSettings(
        backend: String,
        decisionModel: Pair<String, String>? = "nimble:9b" to "ollama",
        threshold: Double = 0.5,
        bandLow: Double = 0.15,
        bandHigh: Double = 0.85
    ) {
        every { configService.getTyped(ConfigKeys.GENERAL_NEXT_SPEAKER_JUDGE_ENABLED, "task-1") } returns true
        every { configService.getTyped(ConfigKeys.GENERAL_JUDGE_EXTENDED_REENTRY_ENABLED, "task-1") } returns false
        every { configService.getTyped(ConfigKeys.GENERAL_JUDGE_BACKEND, "task-1") } returns backend
        every { configService.getTyped(ConfigKeys.GENERAL_JUDGE_DECISION_THRESHOLD, "task-1") } returns threshold
        every { configService.getTyped(ConfigKeys.GENERAL_JUDGE_HYBRID_BAND_LOW, "task-1") } returns bandLow
        every { configService.getTyped(ConfigKeys.GENERAL_JUDGE_HYBRID_BAND_HIGH, "task-1") } returns bandHigh
        every { configService.getDecisionModel("task-1") } returns decisionModel
        every { configService.getModel(ModelOperation.WEAK, "task-1") } returns ("haiku" to "anthropic")
    }

    private fun stubLlmVerdict(speaker: String) {
        coEvery {
            llmClient.complete(
                provider = any(), model = any(), messages = any(), systemPrompt = any(),
                maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                reasoningEffort = any(), noEgressEnabled = any(), stream = any(), onChunk = any(),
                taskId = any(), subtaskId = any(), source = any(), contextContent = any(),
                systemMessages = any(), kwargs = any()
            )
        } returns LLMResponse(
            content = """{"speaker": "$speaker", "reason": "test"}""",
            usage = LLMUsage(150, 20, 170),
            cost = 0.0001,
            model = "haiku",
            provider = "anthropic"
        )
    }

    private fun verifyLlmJudgeCalls(times: Int) {
        coVerify(exactly = times) {
            llmClient.complete(
                provider = any(), model = any(), messages = any(), systemPrompt = any(),
                maxTokens = any(), temperature = any(), responseFormat = any(), thinking = any(),
                reasoningEffort = any(), noEgressEnabled = any(), stream = any(), onChunk = any(),
                taskId = any(), subtaskId = any(), source = any(), contextContent = any(),
                systemMessages = any(), kwargs = any()
            )
        }
    }

    @Test
    fun `claim without evidence sends the agent back without asking the LLM`() = runBlocking {
        // The reply says "compiles" but no build ran: the case every one-question judge let through.
        stubSettings(backend = ConfigKeys.JUDGE_BACKEND_DECISION)
        decisionClient.answers = mapOf("missing" to 0.10, "announces" to 0.02, "unproven" to 0.97)

        val decision = guardian().check(ctx())

        assertTrue(decision is GuardianDecision.Reenter)
        verifyLlmJudgeCalls(0)
        assertEquals(listOf("ollama"), providersAsked)
        val (model, state, questions) = decisionClient.calls.single()
        assertEquals("nimble:9b", model)
        assertEquals(setOf("missing", "announces", "unproven"), questions)
        assertTrue(state.contains("Mode: AGENT"), state)
        assertTrue(state.contains("Fix the bug in config parsing"), state)
        assertTrue(state.contains("the project compiles cleanly"), state)
        assertTrue(state.contains("read_file, code_editing"), state)
    }

    @Test
    fun `delivered work ends the turn without asking the LLM`() = runBlocking {
        stubSettings(backend = ConfigKeys.JUDGE_BACKEND_DECISION)
        decisionClient.answers = mapOf("missing" to 0.04, "announces" to 0.01, "unproven" to 0.03)

        val decision = guardian().check(ctx())

        assertEquals(GuardianDecision.Pass, decision)
        verifyLlmJudgeCalls(0)
    }

    @Test
    fun `a PLAN turn tells the decision model it is in PLAN mode`() = runBlocking {
        // Without the mode a finished plan read as "the requested change is missing".
        stubSettings(backend = ConfigKeys.JUDGE_BACKEND_DECISION)
        decisionClient.answers = mapOf("missing" to 0.05)

        guardian().check(ctx(mode = TaskMode.PLAN, response = "Plan: change A in X.kt, then B in Y.kt, then run the tests."))

        assertTrue(decisionClient.calls.single().second.contains("Mode: PLAN"))
    }

    @Test
    fun `a failing decision model hands the verdict to the LLM judge`() = runBlocking {
        stubSettings(backend = ConfigKeys.JUDGE_BACKEND_DECISION)
        stubLlmVerdict("model")
        decisionClient.failure = DecisionCallException("model 'nimble:9b' not found", httpStatus = 404)

        val decision = guardian().check(ctx())

        assertTrue(decision is GuardianDecision.Reenter)
        verifyLlmJudgeCalls(1)
    }

    @Test
    fun `decision backend without a decision model keeps using the LLM judge`() = runBlocking {
        stubSettings(backend = ConfigKeys.JUDGE_BACKEND_DECISION, decisionModel = null)
        stubLlmVerdict("user")

        val decision = guardian().check(ctx())

        assertEquals(GuardianDecision.Pass, decision)
        verifyLlmJudgeCalls(1)
        assertTrue(decisionClient.calls.isEmpty())
    }

    @Test
    fun `hybrid asks the LLM only when the decision model is unsure`() = runBlocking {
        stubSettings(backend = ConfigKeys.JUDGE_BACKEND_HYBRID)
        stubLlmVerdict("model")
        decisionClient.answers = mapOf("missing" to 0.55)

        val decision = guardian().check(ctx())

        assertTrue(decision is GuardianDecision.Reenter)
        verifyLlmJudgeCalls(1)
        assertEquals(1, decisionClient.calls.size)
    }

    @Test
    fun `hybrid trusts a confident decision model and skips the LLM`() = runBlocking {
        stubSettings(backend = ConfigKeys.JUDGE_BACKEND_HYBRID)
        decisionClient.answers = mapOf("missing" to 0.05, "announces" to 0.02, "unproven" to 0.08)

        val decision = guardian().check(ctx())

        assertEquals(GuardianDecision.Pass, decision)
        verifyLlmJudgeCalls(0)
    }

    @Test
    fun `a goal condition stays with the LLM judge even when the decision backend is selected`() = runBlocking {
        stubSettings(backend = ConfigKeys.JUDGE_BACKEND_DECISION)
        stubLlmVerdict("user")

        val decision = guardian().check(ctx(completionCondition = "all tests in core pass"))

        assertEquals(GuardianDecision.Pass, decision)
        verifyLlmJudgeCalls(1)
        assertTrue(decisionClient.calls.isEmpty())
    }

    @Test
    fun `the default LLM backend never calls the decision model`() = runBlocking {
        stubSettings(backend = ConfigKeys.JUDGE_BACKEND_LLM)
        stubLlmVerdict("user")

        guardian().check(ctx())

        verifyLlmJudgeCalls(1)
        assertTrue(decisionClient.calls.isEmpty())
    }

    @Test
    fun `decision backend continues at or above the threshold and stops below it`() {
        assertEquals(Step.CONTINUE, DecisionNextSpeakerJudge.decisionStep(hybrid = false, pContinue = 0.5, threshold = 0.5, bandLow = 0.15, bandHigh = 0.85))
        assertEquals(Step.FINISHED, DecisionNextSpeakerJudge.decisionStep(hybrid = false, pContinue = 0.49, threshold = 0.5, bandLow = 0.15, bandHigh = 0.85))
        // A model that puts finished turns around 0.5 needs a higher threshold, not a different judge.
        assertEquals(Step.FINISHED, DecisionNextSpeakerJudge.decisionStep(hybrid = false, pContinue = 0.66, threshold = 0.8, bandLow = 0.15, bandHigh = 0.85))
    }

    @Test
    fun `hybrid band edges belong to the LLM and a reversed band is read the right way round`() {
        assertEquals(Step.FINISHED, DecisionNextSpeakerJudge.decisionStep(hybrid = true, pContinue = 0.14, threshold = 0.5, bandLow = 0.15, bandHigh = 0.85))
        assertEquals(Step.ASK_LLM, DecisionNextSpeakerJudge.decisionStep(hybrid = true, pContinue = 0.15, threshold = 0.5, bandLow = 0.15, bandHigh = 0.85))
        assertEquals(Step.ASK_LLM, DecisionNextSpeakerJudge.decisionStep(hybrid = true, pContinue = 0.85, threshold = 0.5, bandLow = 0.15, bandHigh = 0.85))
        assertEquals(Step.CONTINUE, DecisionNextSpeakerJudge.decisionStep(hybrid = true, pContinue = 0.86, threshold = 0.5, bandLow = 0.15, bandHigh = 0.85))
        assertEquals(Step.ASK_LLM, DecisionNextSpeakerJudge.decisionStep(hybrid = true, pContinue = 0.5, threshold = 0.5, bandLow = 0.85, bandHigh = 0.15))
    }
}
