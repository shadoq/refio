package shop.app

import shop.money.Money
import shop.pricing.PriceList
import shop.pricing.UnknownSkuException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CheckoutTest {
    private val checkout = Checkout(PriceList(mapOf("A" to Money(995), "B" to Money(250))))

    @Test
    fun bulkLineThenCoupon() {
        val lines = listOf(OrderLine("A", 10))
        assertEquals(Money(9452), checkout.subtotal(lines))
        assertEquals(Money(8034), checkout.total(lines, couponPercent = 15))
    }

    @Test
    fun noCouponMeansSubtotal() {
        val lines = listOf(OrderLine("B", 4), OrderLine("A", 1))
        assertEquals(Money(1995), checkout.total(lines))
    }

    @Test
    fun sameSkuOnSeveralLinesCountsTowardOneBulkTier() {
        val split = listOf(OrderLine("A", 6), OrderLine("B", 3), OrderLine("A", 6))
        val merged = listOf(OrderLine("A", 12), OrderLine("B", 3))
        assertEquals(checkout.subtotal(merged), checkout.subtotal(split))
        assertEquals(Money(11343 + 750), checkout.subtotal(split))
    }

    @Test
    fun unknownSkuIsReported() {
        assertFailsWith<UnknownSkuException> { checkout.total(listOf(OrderLine("Z", 1))) }
    }
}
