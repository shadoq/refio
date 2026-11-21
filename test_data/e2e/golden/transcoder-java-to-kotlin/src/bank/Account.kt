package bank

import java.util.Collections

class Account(val id: String, private val overdraftLimit: Money) {
    class Entry internal constructor(val memo: String, val amount: Money, val balanceAfter: Money)

    var balance: Money = Money.ZERO
        private set

    private val ledger = mutableListOf<Entry>()

    init {
        require(!overdraftLimit.isNegative) { "overdraft limit must not be negative" }
    }

    val entries: List<Entry>
        get() = Collections.unmodifiableList(ledger)

    fun post(memo: String, amount: Money) {
        val next = balance.plus(amount)
        check(next >= overdraftLimit.negate()) { "overdraft limit exceeded for $id" }
        balance = next
        ledger.add(Entry(memo, amount, next))
    }
}
