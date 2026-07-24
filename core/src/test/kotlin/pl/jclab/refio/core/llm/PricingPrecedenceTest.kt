package pl.jclab.refio.core.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PricingPrecedenceTest {

    private fun definition(input: Double, output: Double, provider: String = "openrouter") = ModelDefinition(
        id = "moonshotai/kimi-k3",
        name = "Kimi K3",
        provider = provider,
        capabilities = emptyList(),
        maxContext = 131072,
        costPer1MInput = input,
        costPer1MOutput = output,
    )

    private fun live(input: Double, output: Double) = ModelConfig(
        id = "moonshotai/kimi-k3",
        name = "Kimi K3",
        provider = "openrouter",
        capabilities = listOf("chat"),
        maxContext = 131072,
        costPer1mInput = input,
        costPer1mOutput = output,
    )

    // The bug: the family-level literal ran ~10x low for the Kimi models. When OpenRouter's own
    // per-model /models price is known it is authoritative and must win over that literal, so a
    // pre-flight estimate is not silently 10x off.
    @Test
    fun `openrouter live price wins over the literal baseline`() {
        val pricing = resolveModelPricing(
            provider = "openrouter",
            definition = definition(input = 0.60, output = 2.50),   // the too-low literal
            cached = live(input = 6.00, output = 25.00),            // the real per-model price
        )

        assertEquals(6.00, pricing.input)
        assertEquals(25.00, pricing.output)
    }

    // With no live price cached (the common case outside a models refresh) the literal is the only
    // number available, so it is still used - the precedence change never removes a fallback.
    @Test
    fun `openrouter falls back to the literal when no live price is cached`() {
        val pricing = resolveModelPricing(
            provider = "openrouter",
            definition = definition(input = 0.60, output = 2.50),
            cached = null,
        )

        assertEquals(0.60, pricing.input)
        assertEquals(2.50, pricing.output)
    }

    // Non-OpenRouter providers keep the original precedence: the curated literal baseline wins and
    // the live cache only fills a 0/0 baseline. Changing OpenRouter must not regress the others.
    @Test
    fun `non-openrouter keeps literal-first precedence`() {
        val pricing = resolveModelPricing(
            provider = "anthropic",
            definition = definition(input = 3.00, output = 15.00, provider = "anthropic"),
            cached = live(input = 999.0, output = 999.0),
        )

        assertEquals(3.00, pricing.input)
        assertEquals(15.00, pricing.output)
    }

    // A definition that exists but is priced 0/0 is an explicit "free"/unknown baseline; with no live
    // price it is returned as free rather than inventing a number.
    @Test
    fun `zero-priced definition with no live price is treated as free`() {
        val pricing = resolveModelPricing(
            provider = "openrouter",
            definition = definition(input = 0.0, output = 0.0),
            cached = null,
        )

        assertEquals(0.0, pricing.input)
        assertEquals(0.0, pricing.output)
    }
}
