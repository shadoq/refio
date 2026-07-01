package com.acme.pricing.internal.calc

/** Computes the final price after a percentage discount. */
object DiscountCalculator {
    /** [pct] is a whole percent: 10 means 10%. Returns the discounted price in cents. */
    fun apply(priceCents: Int, pct: Int): Int {
        // BUG: subtracts the raw percent value instead of pct% of the price,
        // so a 10% discount on $5.00 yields $4.90 instead of $4.50.
        return priceCents - pct
    }
}
