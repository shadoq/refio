package shop.pricing

import shop.money.Money

object BulkDiscount {
    /** Discount percent for a line of [qty] identical units. */
    fun percentFor(qty: Int): Int = when {
        qty >= 50 -> 10
        qty >= 10 -> 5
        else -> 0
    }

    /** Total for [qty] units at [unit] price after the bulk discount. */
    fun lineTotal(unit: Money, qty: Int): Money {
        require(qty > 0) { "qty must be positive" }
        val gross = unit * qty
        return gross - gross.percentOf(percentFor(qty))
    }
}
