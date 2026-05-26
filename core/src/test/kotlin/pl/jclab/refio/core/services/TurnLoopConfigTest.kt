package pl.jclab.refio.core.services

import kotlin.test.Test
import kotlin.test.assertEquals

class TurnLoopConfigTest {

    @Test
    fun `agent config should allow longer runs`() {
        val config = TurnLoopConfig.agent()

        assertEquals(100, config.maxIterations)
        assertEquals(3, config.maxFormatRetries)
    }

    @Test
    fun `plan config should allow more analysis retries`() {
        val config = TurnLoopConfig.plan()

        // PLAN was bumped 50 → 100 (2026-05-26) to match AGENT and align with industry
        // baselines (Gemini CLI 100, Hermes 90). PLAN is read-only so iterations are cheap.
        assertEquals(100, config.maxIterations)
        assertEquals(3, config.maxFormatRetries)
    }
}
