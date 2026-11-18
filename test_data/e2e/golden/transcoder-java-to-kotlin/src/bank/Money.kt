package bank

import java.util.Locale

class Money private constructor(val cents: Long) : Comparable<Money> {
    val isNegative: Boolean
        get() = cents < 0

    fun plus(other: Money): Money = Money(Math.addExact(cents, other.cents))

    fun minus(other: Money): Money = Money(Math.subtractExact(cents, other.cents))

    fun negate(): Money = Money(Math.negateExact(cents))

    override fun compareTo(other: Money): Int = cents.compareTo(other.cents)

    override fun equals(other: Any?): Boolean = other is Money && cents == other.cents

    override fun hashCode(): Int = cents.hashCode()

    override fun toString(): String {
        val abs = Math.abs(cents)
        return String.format(Locale.ROOT, "%s$%d.%02d", if (cents < 0) "-" else "", abs / 100, abs % 100)
    }

    companion object {
        val ZERO = Money(0L)

        fun ofCents(cents: Long): Money = Money(cents)
    }
}
