package shop.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {
    @Test
    fun percentOfRoundsHalfUp() {
        assertEquals(Money(105), Money(1050).percentOf(10))
        assertEquals(Money(503), Money(1005).percentOf(50))
        assertEquals(Money(50), Money(995).percentOf(5))
        assertEquals(Money(0), Money(9).percentOf(5))
    }

    @Test
    fun percentOfRejectsBadInput() {
        assertFailsWith<IllegalArgumentException> { Money(100).percentOf(101) }
        assertFailsWith<IllegalArgumentException> { Money(-100).percentOf(10) }
    }

    @Test
    fun arithmeticAndFormatting() {
        assertEquals(Money(350), Money(100) + Money(250))
        assertEquals(Money(150), Money(250) - Money(100))
        assertEquals(Money(750), Money(250) * 3)
        assertEquals("12.05", Money(1205).toString())
    }
}
