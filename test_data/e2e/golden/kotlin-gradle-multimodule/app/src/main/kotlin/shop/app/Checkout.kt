package shop.app

import shop.money.Money
import shop.pricing.BulkDiscount
import shop.pricing.PriceList

data class OrderLine(val sku: String, val qty: Int)

class Checkout(private val prices: PriceList) {
    /** Sum of the discounted line totals. */
    fun subtotal(lines: List<OrderLine>): Money =
        lines.groupBy { it.sku }
            .map { (sku, group) -> BulkDiscount.lineTotal(prices.unitPrice(sku), group.sumOf { it.qty }) }
            .fold(Money.ZERO) { acc, total -> acc + total }

    /** Subtotal minus a coupon percent that is taken off the discounted subtotal. */
    fun total(lines: List<OrderLine>, couponPercent: Int = 0): Money {
        val sub = subtotal(lines)
        return sub - sub.percentOf(couponPercent)
    }
}
