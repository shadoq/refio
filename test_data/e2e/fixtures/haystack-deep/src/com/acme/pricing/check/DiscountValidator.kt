package com.acme.pricing.check

/** Validates a discount percentage. Correct as-is - do not change. */
object DiscountValidator {
    fun isValid(pct: Int): Boolean = pct in 0..100
}
