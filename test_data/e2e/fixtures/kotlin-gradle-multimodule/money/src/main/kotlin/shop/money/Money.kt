package shop.money

/** A non-negative-friendly amount of money held in whole cents. */
data class Money(val cents: Long) : Comparable<Money> {
    operator fun plus(other: Money): Money = Money(cents + other.cents)

    operator fun minus(other: Money): Money = Money(cents - other.cents)

    operator fun times(n: Int): Money = Money(cents * n)

    /** [pct] percent of this amount, rounded half up to a whole cent. */
    fun percentOf(pct: Int): Money {
        require(cents >= 0) { "percentOf needs a non-negative amount" }
        require(pct in 0..100) { "percent out of range: $pct" }
        return Money((cents * pct + 50) / 100)
    }

    override fun compareTo(other: Money): Int = cents.compareTo(other.cents)

    override fun toString(): String = "%d.%02d".format(cents / 100, cents % 100)

    companion object {
        val ZERO = Money(0)
    }
}
