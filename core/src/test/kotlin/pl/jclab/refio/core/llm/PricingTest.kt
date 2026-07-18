package pl.jclab.refio.core.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Cost accounting rules. The billing contract is: input tokens split into fresh / cache-read /
 * cache-write subsets, each priced at its own rate, so a repeated prompt (served from cache) is
 * not billed as if it were fresh. gpt-5.4-nano is the reference: input $0.20, cached $0.02,
 * output $1.25 per 1M tokens.
 */
class PricingTest {

    private val eps = 1e-6

    @Test
    fun `without cache the whole input is billed at the fresh rate (legacy call is unchanged)`() {
        // The pre-cache signature must keep costing the same, so existing callers are unaffected.
        val cost = calculateCost("openai", "gpt-5.4-nano", inputTokens = 1_000_000, outputTokens = 0)
        assertEquals(0.20, cost, eps)
    }

    @Test
    fun `a cached input token is billed at the cache rate, not the fresh rate`() {
        // 1M tokens all served from cache: $0.02, not $0.20 - the whole point of the feature.
        val cost = calculateCost(
            "openai", "gpt-5.4-nano",
            inputTokens = 1_000_000, outputTokens = 0, cachedInputTokens = 1_000_000
        )
        assertEquals(0.02, cost, eps)
    }

    @Test
    fun `cached tokens are a subset of input, so only the fresh remainder pays the full rate`() {
        // 1M input of which 0.6M cached: 0.4M @ $0.20 + 0.6M @ $0.02 = 0.08 + 0.012 = 0.092.
        val cost = calculateCost(
            "openai", "gpt-5.4-nano",
            inputTokens = 1_000_000, outputTokens = 0, cachedInputTokens = 600_000
        )
        assertEquals(0.092, cost, eps)
    }

    @Test
    fun `a model with no known cache price bills cached tokens at the full input rate (conservative)`() {
        // Option B: without an explicit cache price the discount is NOT assumed, so an unknown
        // cache never lowers the estimate. gpt-5 has input $1.25 and no cache price configured.
        val fresh = calculateCost("openai", "gpt-5", inputTokens = 1_000_000, outputTokens = 0)
        val cached = calculateCost(
            "openai", "gpt-5",
            inputTokens = 1_000_000, outputTokens = 0, cachedInputTokens = 1_000_000
        )
        assertEquals(fresh, cached, eps)
        assertEquals(1.25, cached, eps)
    }

    @Test
    fun `the real nano e2e run reconciles to the OpenAI bill of 1_66 dollars`() {
        // Measured billing: 18.75M input (5.25M fresh + 13.5M cache-read) + 0.270746M output.
        // 5.25*0.20 + 13.5*0.02 + 0.270746*1.25 = 1.05 + 0.27 + 0.3384 = 1.6584.
        val cost = calculateCost(
            "openai", "gpt-5.4-nano",
            inputTokens = 18_750_000, outputTokens = 270_746, cachedInputTokens = 13_500_000
        )
        assertTrue(kotlin.math.abs(cost - 1.66) < 0.01, "expected ~\$1.66, got \$$cost")
    }

    @Test
    fun `ignoring the cache overcharges the same run to about 4 dollars`() {
        // Same tokens, cache ignored (the pre-fix behaviour): 18.75M @ $0.20 + output = ~$4.09.
        // This is why cache-awareness matters for repeated-context workloads.
        val cost = calculateCost("openai", "gpt-5.4-nano", inputTokens = 18_750_000, outputTokens = 270_746)
        assertTrue(cost > 4.0, "expected >\$4 without cache accounting, got \$$cost")
    }

    @Test
    fun `anthropic cache-read is billed at one tenth of input, not the full rate (10x regression)`() {
        // claude-sonnet-5: input $3.00, cache-read $0.30. Before this fix a cache hit was billed at
        // the full input rate - a 10x overcharge on repeated context.
        val cached = calculateCost(
            "anthropic", "claude-sonnet-5",
            inputTokens = 1_000_000, outputTokens = 0, cachedInputTokens = 1_000_000
        )
        assertEquals(0.30, cached, eps)
    }

    @Test
    fun `gemini cache-read is billed at one quarter of input`() {
        // gemini-2.5-pro: input $1.25, cache-read $0.3125 (25% discount tier).
        val cached = calculateCost(
            "gemini", "gemini-2.5-pro",
            inputTokens = 1_000_000, outputTokens = 0, cachedInputTokens = 1_000_000
        )
        assertEquals(0.3125, cached, eps)
    }

    @Test
    fun `explicit cache price on the definition overrides the fallback`() {
        // getModelPricing must surface the configured cache rate, not the input rate.
        val pricing = getModelPricing("openai", "gpt-5.4-nano")
        assertEquals(0.20, pricing.input, eps)
        assertEquals(0.02, pricing.cachedInput, eps)
    }
}
