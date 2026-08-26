package pl.jclab.refio.core.services.turn

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage

/**
 * Pins the weak-model recovery decision for empty content in JSON mode (docs/0058, Faza 1).
 *
 * This is pure classification — no persistence, no finalization, no loop control. The turn loop
 * executes the side effects (re-bind / persist + continue / fail); here we only assert WHICH
 * decision a given response yields, so the qwen-style "JSON envelope landed in `thinking`" rescue
 * and the bounded nudge policy are testable in isolation.
 */
class LLMResponseRecoveryTest {

    private val toolCallParser = mockk<ToolCallParser>()
    private val recovery = LLMResponseRecovery(toolCallParser)

    private fun resp(
        content: String = "",
        thinking: String? = null,
        native: List<pl.jclab.refio.core.llm.NativeToolCall>? = null,
    ): LLMResponse = LLMResponse(
        content = content,
        usage = LLMUsage(0, 0, 0),
        model = "test-model",
        provider = "test",
        cost = 0.0,
        thinking = thinking,
        nativeToolCalls = native,
    )

    private fun stubThinkingParse(result: List<ToolCallData>) {
        every { toolCallParser.extractToolCalls(any(), any(), any()) } returns result
    }

    private fun classify(
        response: LLMResponse,
        mode: TaskMode = TaskMode.AGENT,
        jsonMode: Boolean = true,
        iteration: Int = 1,
        maxIterations: Int = 50,
        state: RecoveryState = RecoveryState(),
        hasRestorableAnswer: Boolean = false,
    ) = recovery.classifyEmptyContent(
        response,
        mode,
        jsonMode,
        iteration,
        maxIterations,
        state,
        hasRestorableAnswer = hasRestorableAnswer,
    )

    @Test
    fun `recovers when the JSON envelope landed in the thinking field`() {
        // qwen3 with think=true streams the actions envelope into `thinking`, content stays empty.
        stubThinkingParse(listOf(ToolCallData("1", "read_file", "{}")))
        val thinking = "Let me call a tool: {\"actions\":[{\"tool\":\"read_file\",\"args\":{}}]}"

        val decision = classify(resp(content = "", thinking = thinking))

        val recovered = assertIs<LLMResponseRecovery.Decision.RecoverFromThinking>(decision)
        assertEquals(thinking, recovered.newContent)
    }

    @Test
    fun `recovers when thinking only looks like an envelope`() {
        // A final-response envelope without `actions` won't parse to tool calls, but a leading `{`
        // is enough — the downstream pipeline handles the response-only shape.
        stubThinkingParse(emptyList())

        val decision = classify(resp(content = "", thinking = "{\"response\":\"done\"}"))

        assertIs<LLMResponseRecovery.Decision.RecoverFromThinking>(decision)
    }

    @Test
    fun `nudges in AGENT mode when content is empty and budget remains`() {
        // Weak models drop format for one iteration; a single short nudge usually brings them back.
        val decision = classify(resp(content = ""), state = RecoveryState(nudgeCount = 0))

        assertIs<LLMResponseRecovery.Decision.Nudge>(decision)
    }

    @Test
    fun `gives up after two nudges instead of spinning`() {
        // Bounded to 2: if two explicit reminders didn't help, further retries won't either.
        val decision = classify(resp(content = ""), state = RecoveryState(nudgeCount = 2))

        assertIs<LLMResponseRecovery.Decision.GiveUp>(decision)
    }

    @Test
    fun `gives up immediately when a guardian re-entry already holds the answer`() {
        // The re-entry is the last safety net and it produced nothing: the answer the user saw is
        // already stashed, so nudging only burns full-context iterations before the same restore.
        val decision = classify(
            resp(content = ""),
            state = RecoveryState(nudgeCount = 0),
            hasRestorableAnswer = true,
        )

        assertIs<LLMResponseRecovery.Decision.GiveUp>(decision)
    }

    @Test
    fun `still recovers a thinking envelope even after a guardian re-entry`() {
        // A recoverable envelope is real work — it must win over the give-up shortcut.
        stubThinkingParse(listOf(ToolCallData("1", "read_file", "{}")))

        val decision = classify(
            resp(content = "", thinking = "{\"actions\":[{\"tool\":\"read_file\",\"args\":{}}]}"),
            hasRestorableAnswer = true,
        )

        assertIs<LLMResponseRecovery.Decision.RecoverFromThinking>(decision)
    }

    @Test
    fun `gives up when the iteration budget is exhausted`() {
        val decision = classify(resp(content = ""), iteration = 50, maxIterations = 50)

        assertIs<LLMResponseRecovery.Decision.GiveUp>(decision)
    }

    @Test
    fun `gives up without nudging outside AGENT mode`() {
        // PLAN never opted into the recover-by-nudge contract — empty content fails immediately.
        val decision = classify(resp(content = ""), mode = TaskMode.PLAN)

        assertIs<LLMResponseRecovery.Decision.GiveUp>(decision)
    }

    @Test
    fun `not applicable when content is present`() {
        val decision = classify(resp(content = "here is the answer"))

        assertIs<LLMResponseRecovery.Decision.NotApplicable>(decision)
    }

    @Test
    fun `not applicable when native tool calls are present`() {
        val decision = classify(
            resp(content = "", native = listOf(pl.jclab.refio.core.llm.NativeToolCall("1", "read_file", "{}"))),
        )

        assertIs<LLMResponseRecovery.Decision.NotApplicable>(decision)
    }

    @Test
    fun `not applicable in native tools mode`() {
        // The symmetric native-empty branch is handled separately in the loop, not here.
        val decision = classify(resp(content = ""), jsonMode = false)

        assertIs<LLMResponseRecovery.Decision.NotApplicable>(decision)
    }

    @Test
    fun `not applicable in CHAT mode`() {
        val decision = classify(resp(content = ""), mode = TaskMode.CHAT)

        assertIs<LLMResponseRecovery.Decision.NotApplicable>(decision)
    }
}
