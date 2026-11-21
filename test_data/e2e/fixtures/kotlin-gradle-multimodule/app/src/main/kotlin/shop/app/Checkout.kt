package shop.app

import shop.money.Money
import shop.pricing.BulkDiscount
import shop.pricing.PriceList

data class OrderLine(val sku: String, val qty: Int)

class Checkout(private val prices: PriceList) {
    /** Sum of the discounted line totals. */
    fun subtotal(lines: List<OrderLine>): Money =
        lines.fold(Money.ZERO) { acc, line ->
            acc + BulkDiscount.lineTotal(prices.unitPrice(line.sku), line.qty)
        }

    /** Subtotal minus a coupon percent that is taken off the discounted subtotal. */
    fun total(lines: List<OrderLine>, couponPercent: Int = 0): Money {
        val sub = subtotal(lines)
        return sub - sub.percentOf(couponPercent)
    }
}
