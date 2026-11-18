import bank.Account
import bank.Money
import bank.Statement

var failures = 0

fun check(name: String, block: () -> Unit) {
    try {
        block()
        println("ok   $name")
    } catch (t: Throwable) {
        failures++
        println("FAIL $name: ${t.javaClass.simpleName}: ${t.message}")
    }
}

fun expect(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

fun <T> expectEq(actual: T, expected: T) {
    if (actual != expected) throw AssertionError("expected <$expected> but was <$actual>")
}

inline fun <reified E : Throwable> expectThrows(message: String?, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        if (t !is E) throw AssertionError("expected ${E::class.java.simpleName} but got ${t.javaClass.simpleName}")
        if (message != null && t.message != message) throw AssertionError("message was <${t.message}>")
        return
    }
    throw AssertionError("expected ${E::class.java.simpleName} but nothing was thrown")
}

fun main() {
    check("money arithmetic") {
        val a = Money.ofCents(1050)
        val b = Money.ofCents(295)
        expectEq(a.plus(b).cents, 1345L)
        expectEq(a.minus(b).cents, 755L)
        expectEq(b.minus(a).cents, -755L)
        expectEq(a.negate().cents, -1050L)
        expect(b.minus(a).isNegative, "negative result should be negative")
        expect(!Money.ZERO.isNegative, "zero is not negative")
    }
    check("money overflow throws") {
        expectThrows<ArithmeticException>(null) { Money.ofCents(Long.MAX_VALUE).plus(Money.ofCents(1)) }
        expectThrows<ArithmeticException>(null) { Money.ofCents(Long.MIN_VALUE).minus(Money.ofCents(1)) }
        expectThrows<ArithmeticException>(null) { Money.ofCents(Long.MIN_VALUE).negate() }
    }
    check("money value equality and hashing") {
        expectEq(Money.ofCents(5), Money.ofCents(5))
        expectEq(Money.ZERO, Money.ofCents(0))
        expect(Money.ofCents(5) != Money.ofCents(6), "different amounts differ")
        expectEq(setOf(Money.ofCents(7), Money.ofCents(7), Money.ofCents(8)).size, 2)
        expectEq(Money.ofCents(42).hashCode(), Money.ofCents(42).hashCode())
    }
    check("money ordering") {
        val sorted = listOf(Money.ofCents(30), Money.ofCents(-5), Money.ofCents(10)).sorted()
        expectEq(sorted.map { it.cents }, listOf(-5L, 10L, 30L))
        expect(Money.ofCents(1) < Money.ofCents(2), "compareTo")
    }
    check("money text") {
        expectEq(Money.ofCents(1234).toString(), "\$12.34")
        expectEq(Money.ofCents(5).toString(), "\$0.05")
        expectEq(Money.ofCents(-5).toString(), "-\$0.05")
        expectEq(Money.ofCents(-123456).toString(), "-\$1234.56")
        expectEq(Money.ZERO.toString(), "\$0.00")
    }
    check("account posting and balance") {
        val acc = Account("ACC-1", Money.ofCents(500))
        expectEq(acc.id, "ACC-1")
        acc.post("salary", Money.ofCents(10000))
        acc.post("rent", Money.ofCents(-10300))
        expectEq(acc.balance, Money.ofCents(-300))
        expectEq(acc.entries.size, 2)
        expectEq(acc.entries[1].memo, "rent")
        expectEq(acc.entries[1].amount, Money.ofCents(-10300))
        expectEq(acc.entries[1].balanceAfter, Money.ofCents(-300))
    }
    check("overdraft limit is inclusive and a rejected post changes nothing") {
        val acc = Account("ACC-2", Money.ofCents(500))
        acc.post("edge", Money.ofCents(-500))
        expectThrows<IllegalStateException>("overdraft limit exceeded for ACC-2") { acc.post("one more", Money.ofCents(-1)) }
        expectEq(acc.balance, Money.ofCents(-500))
        expectEq(acc.entries.size, 1)
    }
    check("negative overdraft limit is rejected") {
        expectThrows<IllegalArgumentException>("overdraft limit must not be negative") { Account("X", Money.ofCents(-1)) }
    }
    check("entries cannot be modified through the getter") {
        val acc = Account("ACC-3", Money.ZERO)
        acc.post("in", Money.ofCents(100))
        acc.post("in again", Money.ofCents(100))
        @Suppress("UNCHECKED_CAST")
        val view = acc.entries as MutableList<Account.Entry>
        expectThrows<UnsupportedOperationException>(null) { view.clear() }
        expectEq(acc.entries.size, 2)
    }
    check("statement layout") {
        val acc = Account("ACC-4", Money.ofCents(1000))
        acc.post("salary", Money.ofCents(250000))
        acc.post("a very long memo text", Money.ofCents(-1999))
        val expected = listOf(
            "Statement for ACC-4",
            "salary         \$2500.00   \$2500.00",
            "a very long     -\$19.99   \$2480.01",
            "Balance: \$2480.01",
        ).joinToString("\n")
        expectEq(Statement.render(acc), expected)
    }
    check("statement of an empty account") {
        expectEq(Statement.render(Account("ACC-5", Money.ZERO)), "Statement for ACC-5\n(no entries)\nBalance: \$0.00")
    }
    if (failures > 0) {
        println("$failures test(s) failed")
        System.exit(1)
    }
    println("all tests passed")
}
