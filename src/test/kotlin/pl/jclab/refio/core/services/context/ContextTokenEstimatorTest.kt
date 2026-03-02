package pl.jclab.refio.core.services.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextTokenEstimatorTest {

    @Test
    fun `truncateToTokens should not exceed requested token budget`() {
        val maxTokens = 100
        val longText = "a".repeat(10_000)

        val truncated = ContextTokenEstimator.truncateToTokens(longText, maxTokens)
        val estimatedTokens = ContextTokenEstimator.estimateTokens(truncated)

        assertTrue(
            estimatedTokens <= maxTokens,
            "Expected <= $maxTokens tokens, got $estimatedTokens"
        )
    }

    @Test
    fun `truncateToTokens should keep text unchanged when already within budget`() {
        val text = "abc".repeat(100) // 300 chars -> 75 tokens

        val truncated = ContextTokenEstimator.truncateToTokens(text, 100)

        assertEquals(text, truncated)
    }
}
