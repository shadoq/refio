package pl.jclab.refio.core.llm.streaming

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StreamGuardrailsTest {

    /** Guardrail that always continues. */
    private class AlwaysOk : StreamGuardrail {
        override val name = "ok"
        override fun onDelta(delta: String, accumulatedLength: Int, tail: String, streamStartMs: Long) =
            StreamGuardrail.Decision.Continue
    }

    /** Guardrail that aborts on the Nth call. */
    private class AbortOnCall(private val n: Int, private val code: String) : StreamGuardrail {
        override val name = "abort-on-$n"
        private var calls = 0
        override fun onDelta(delta: String, accumulatedLength: Int, tail: String, streamStartMs: Long): StreamGuardrail.Decision {
            calls++
            return if (calls >= n) StreamGuardrail.Decision.Abort(code, "fired at call $calls")
            else StreamGuardrail.Decision.Continue
        }
    }

    /** Guardrail that records every invocation for inspection. */
    private class Recorder : StreamGuardrail {
        override val name = "recorder"
        val tails = mutableListOf<String>()
        val deltas = mutableListOf<String>()
        val lengths = mutableListOf<Int>()
        override fun onDelta(delta: String, accumulatedLength: Int, tail: String, streamStartMs: Long): StreamGuardrail.Decision {
            deltas += delta
            tails += tail
            lengths += accumulatedLength
            return StreamGuardrail.Decision.Continue
        }
    }

    @Test
    fun `empty delta is a no-op`() {
        val recorder = Recorder()
        val guardrails = StreamGuardrails(listOf(recorder))
        assertEquals(StreamGuardrail.Decision.Continue, guardrails.check(""))
        assertTrue(recorder.deltas.isEmpty(), "Empty delta should not invoke guardrails")
        assertEquals(0, guardrails.accumulatedContent().length)
    }

    @Test
    fun `accumulates content across deltas`() {
        val recorder = Recorder()
        val guardrails = StreamGuardrails(listOf(recorder))
        guardrails.check("hello ")
        guardrails.check("world")
        assertEquals("hello world", guardrails.accumulatedContent())
        assertEquals(listOf(6, 11), recorder.lengths)
    }

    @Test
    fun `first abort short-circuits remaining guardrails`() {
        val first = AbortOnCall(n = 1, code = "FIRST")
        val second = Recorder()
        val guardrails = StreamGuardrails(listOf(first, second))
        val decision = guardrails.check("boom")
        assertTrue(decision is StreamGuardrail.Decision.Abort)
        assertEquals("FIRST", decision.code)
        assertTrue(second.deltas.isEmpty(), "Second guardrail must not be called after first aborts")
    }

    @Test
    fun `guardrails run in declared order`() {
        val first = AlwaysOk()
        val second = AbortOnCall(n = 1, code = "SECOND")
        val guardrails = StreamGuardrails(listOf(first, second))
        val decision = guardrails.check("boom")
        assertTrue(decision is StreamGuardrail.Decision.Abort)
        assertEquals("SECOND", decision.code)
    }

    @Test
    fun `tail is bounded by tailSize`() {
        val recorder = Recorder()
        val guardrails = StreamGuardrails(listOf(recorder), tailSize = 100)
        // Push 250 chars of unique content.
        val payload = (0..249).joinToString("") { "${it % 10}" }
        guardrails.check(payload)
        assertEquals(1, recorder.tails.size)
        assertEquals(100, recorder.tails[0].length, "Tail must be capped at tailSize")
        // Must be the LAST 100 chars, not the first 100.
        assertEquals(payload.takeLast(100), recorder.tails[0])
    }

    @Test
    fun `tail equals full content while below tailSize`() {
        val recorder = Recorder()
        val guardrails = StreamGuardrails(listOf(recorder), tailSize = 1000)
        guardrails.check("short content")
        assertEquals("short content", recorder.tails[0])
    }

    @Test
    fun `accumulatedContent() returns entire content even after tail truncation`() {
        val guardrails = StreamGuardrails(listOf(Recorder()), tailSize = 50)
        val longPayload = "x".repeat(200)
        guardrails.check(longPayload)
        assertEquals(200, guardrails.accumulatedContent().length)
    }

    @Test
    fun `defaults composition has three guardrails`() {
        val g = StreamGuardrails.defaults()
        val names = g.activeGuardrailNames()
        assertEquals(3, names.size)
        assertTrue("repetition" in names)
        assertTrue("size-limit" in names)
        assertTrue("wall-clock-deadline" in names)
    }

    @Test
    fun `none() composition has zero guardrails and never aborts`() {
        val g = StreamGuardrails.none()
        assertEquals(0, g.activeGuardrailNames().size)
        assertEquals(StreamGuardrail.Decision.Continue, g.check("x".repeat(100_000)))
    }

    @Test
    fun `stream start timestamp is captured at construction and stable`() {
        val g = StreamGuardrails(listOf(Recorder()))
        val start = g.streamStartMillis()
        Thread.sleep(5)
        g.check("something")
        assertEquals(start, g.streamStartMillis(), "Start timestamp must not drift")
    }

    @Test
    fun `integration — qwen style repetition loop is caught by defaults`() {
        // Simulate the domatowo failure: 50+ identical 300-char blocks streamed
        // as separate deltas. Defaults should fire within the first few rounds
        // after crossing the threshold.
        val guardrails = StreamGuardrails.defaults()
        val loopBlock = "Let me execute this Python code to move the transporter to D8:\n" +
            "```python\nimport requests\nAPI_KEY = \"e14b59fb-57e9-475c-8b1a-c64c6ed1660f\"\n" +
            "result = requests.post(BASE_URL, json={\"action\": \"move\", \"where\": \"D8\"})\n" +
            "print(result.json())\n```\n"
        // Gate default is 20 — feed 200 rounds to guarantee a check runs.
        var aborted = false
        var abortCode: String? = null
        repeat(200) {
            val decision = guardrails.check(loopBlock)
            if (decision is StreamGuardrail.Decision.Abort) {
                aborted = true
                abortCode = decision.code
                return@repeat
            }
        }
        assertTrue(aborted, "Default guardrails must catch this loop pattern")
        // Should be the repetition detector that fires first, not size limit.
        assertEquals("REPETITION_LOOP", abortCode)
    }
}
