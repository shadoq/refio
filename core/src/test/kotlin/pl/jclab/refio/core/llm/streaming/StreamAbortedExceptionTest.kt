package pl.jclab.refio.core.llm.streaming

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamAbortedExceptionTest {

    @Test
    fun `is a CancellationException`() {
        // This is the whole point — CancellationException is treated specially by
        // Kotlin coroutines and by our adapter catch blocks that explicitly rethrow it.
        // Use a runtime Throwable reference to dodge the always-true warning.
        val ex: Throwable = StreamAbortedException("TEST", "why", "partial data")
        assertTrue(ex is CancellationException, "Must extend CancellationException")
    }

    @Test
    fun `carries code, reason and partial content`() {
        val ex = StreamAbortedException("REPETITION_LOOP", "4 blocks", "the content so far")
        assertEquals("REPETITION_LOOP", ex.code)
        // `reason` is exposed as a field so AgentTurnLoop's StreamAborted event can
        // emit it without re-parsing message. Regression: previously only embedded in message.
        assertEquals("4 blocks", ex.reason)
        assertEquals("the content so far", ex.partialContent)
        assertTrue(ex.message!!.contains("REPETITION_LOOP"))
        assertTrue(ex.message!!.contains("4 blocks"))
    }

    @Test
    fun `partial content can be large without truncation`() {
        val large = "x".repeat(50_000)
        val ex = StreamAbortedException("OUTPUT_TOO_LARGE", "too big", large)
        assertEquals(50_000, ex.partialContent.length)
    }
}
