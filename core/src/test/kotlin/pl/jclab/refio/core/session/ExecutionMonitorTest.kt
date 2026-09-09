package pl.jclab.refio.core.session

import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Locks that the Stop button actually reaches the running turn.
 *
 * WHY this matters: cancelStreaming() cancels whatever job it was handed. With nothing handing it
 * one, Stop degrades to a flag that only cooperative polling points notice - a turn parked in a
 * non-streamed LLM call (summarizer, verifier, JSON mode) keeps running for minutes after the user
 * asked it to stop.
 */
class ExecutionMonitorTest {

    private val monitor = ExecutionMonitor(
        stateManager = mockk(relaxed = true),
        stepExecutionService = mockk(relaxed = true)
    )

    @Test
    fun `cancelStreaming aborts the turn job it was given`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            started.complete(Unit)
            kotlinx.coroutines.delay(30_000)
        }
        monitor.trackStreamingJob(job)

        started.await()
        monitor.cancelStreaming()

        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled, "Stop must cancel the registered turn job")
    }
}
