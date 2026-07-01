package com.acme.pricing.format

/** Renders a discount for display. Correct as-is - do not change. */
object DiscountFormatter {
    fun label(pct: Int): String = "$pct% off"
}
