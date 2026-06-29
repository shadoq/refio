package com.acme.catalog.calc

/** Unrelated price helper. Correct as-is - do not change. */
object PriceCalculator {
    fun withTax(priceCents: Int, taxPct: Int): Int = priceCents + priceCents * taxPct / 100
}
