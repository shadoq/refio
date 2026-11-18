package shop.pricing

import shop.money.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BulkDiscountTest {
    @Test
    fun tiersStartAtTenAndFifty() {
        assertEquals(0, BulkDiscount.percentFor(9))
        assertEquals(5, BulkDiscount.percentFor(10))
        assertEquals(5, BulkDiscount.percentFor(49))
        assertEquals(10, BulkDiscount.percentFor(50))
    }

    @Test
    fun smallLinesAreNotDiscounted() {
        assertEquals(Money(3000), BulkDiscount.lineTotal(Money(1000), 3))
    }

    @Test
    fun discountIsTakenFromTheWholeLineAndRoundedOnce() {
        assertEquals(Money(9452), BulkDiscount.lineTotal(Money(995), 10))
        assertEquals(Money(14985), BulkDiscount.lineTotal(Money(333), 50))
    }

    @Test
    fun quantityMustBePositive() {
        assertFailsWith<IllegalArgumentException> { BulkDiscount.lineTotal(Money(100), 0) }
    }

    @Test
    fun priceListThrowsForUnknownSku() {
        val list = PriceList(mapOf("A" to Money(100)))
        assertEquals(Money(100), list.unitPrice("A"))
        assertFailsWith<UnknownSkuException> { list.unitPrice("B") }
    }
}
