package pl.jclab.refio.core.llm.adapters

import org.junit.jupiter.api.Test
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.calculateCost
import kotlin.test.assertEquals

/**
 * Cost accounting for OpenRouter.
 *
 * The bug (session 92a771c6, moonshotai/kimi-k3): the GUI reported ~$0.26 for a session that the
 * OpenRouter dashboard billed at ~$2.6 - a ~10x undercount. Root cause: cost was computed from a
 * hardcoded family-baseline price ($0.60/$2.50 per 1M) in ModelDefinitions, which was ~10x too low
 * and shadowed the accurate live price.
 *
 * OpenRouter's response `usage` object always carries the actual amount charged in `cost` (credits,
 * 1 credit == $1 - the exact number its dashboard shows). Reporting THAT makes the GUI match the
 * dashboard by construction and removes the reliance on hand-maintained price literals. The local
 * per-1M estimate stays only as a fallback for the rare case a provider omits `cost`.
 */
class OpenRouterCostAccountingTest {

    @Test
    fun `estimateCost reports OpenRouter's own charged cost, not the local per-1M estimate`() {
        val adapter = OpenRouterAdapter(model = "moonshotai/kimi-k3")
        // A real turn from the report: 58089 in / 772 out. OpenRouter charged $0.37; the hardcoded
        // baseline would estimate only ~$0.0368 (58089 * 0.60/1M + 772 * 2.50/1M) - the 10x gap.
        val usage = LLMUsage(
            inputTokens = 58089,
            outputTokens = 772,
            totalTokens = 58861,
            upstreamCostUsd = 0.37,
        )
        assertEquals(0.37, adapter.estimateCost(usage), 1e-9)
    }

    @Test
    fun `estimateCost falls back to the local estimate when OpenRouter omits cost`() {
        val adapter = OpenRouterAdapter(model = "moonshotai/kimi-k3")
        val usage = LLMUsage(inputTokens = 1_000_000, outputTokens = 0, totalTokens = 1_000_000)
        // No upstream cost -> unchanged legacy behaviour (local per-1M estimate).
        val expected = calculateCost("openrouter", "moonshotai/kimi-k3", 1_000_000, 0)
        assertEquals(expected, adapter.estimateCost(usage), 1e-9)
    }
}
