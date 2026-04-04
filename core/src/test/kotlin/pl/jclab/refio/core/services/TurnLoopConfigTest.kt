package pl.jclab.refio.core.services

import kotlin.test.Test
import kotlin.test.assertEquals

class TurnLoopConfigTest {

    @Test
    fun `agent config should allow longer runs`() {
        val config = TurnLoopConfig.agent()

        assertEquals(100, config.maxIterations)
        assertEquals(15, config.maxConsecutiveReadOnlyIterations)
        assertEquals(3, config.maxFormatRetries)
    }

    @Test
    fun `plan config should allow more analysis retries`() {
        val config = TurnLoopConfig.plan()

        assertEquals(50, config.maxIterations)
        assertEquals(15, config.maxConsecutiveReadOnlyIterations)
        assertEquals(3, config.maxFormatRetries)
    }
}
