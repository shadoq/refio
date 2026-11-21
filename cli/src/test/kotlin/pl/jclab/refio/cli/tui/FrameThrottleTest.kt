package pl.jclab.refio.cli.tui

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameThrottleTest {

    @Test
    fun `streaming bursts draw at most one frame per interval and never lose the last state`() = runTest {
        val state = MutableStateFlow(0)
        val drawn = mutableListOf<Int>()
        val job = launch { state.throttleLatest(33).collect { drawn += it } }

        repeat(100) {
            state.value = it + 1
            delay(1)
        }
        advanceUntilIdle()
        job.cancel()

        assertTrue(drawn.size <= 5, "drew ${drawn.size} frames for 100 ms of updates: $drawn")
        assertEquals(100, drawn.last())
    }

    @Test
    fun `a single change after a quiet period is drawn at once`() = runTest {
        val state = MutableStateFlow(0)
        val drawn = mutableListOf<Int>()
        val job = launch { state.throttleLatest(33).collect { drawn += it } }

        delay(500)
        state.value = 1
        delay(1)
        job.cancel()

        assertEquals(listOf(0, 1), drawn)
    }
}
