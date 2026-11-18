import kotlin.system.exitProcess

private var failures = 0

private fun test(name: String, body: () -> Unit) {
    try {
        body()
        println("ok   $name")
    } catch (t: Throwable) {
        failures++
        println("FAIL $name: $t")
    }
}

private fun norm(d: Pair<Int, Int>) = if (d.first <= d.second) d else Pair(d.second, d.first)

private fun assertChain(input: List<Pair<Int, Int>>) {
    val chain = findChain(input) ?: throw AssertionError("expected a chain for $input but got null")
    if (chain.map(::norm).sortedBy { it.first * 1000 + it.second } != input.map(::norm).sortedBy { it.first * 1000 + it.second }) {
        throw AssertionError("chain $chain does not use exactly the input dominoes $input")
    }
    if (chain.isEmpty()) return
    if (norm(chain[0]) != norm(input[0])) throw AssertionError("chain $chain does not start with ${input[0]}")
    for (i in 0 until chain.size - 1) {
        if (chain[i].second != chain[i + 1].first) throw AssertionError("chain $chain breaks between index $i and ${i + 1}")
    }
    if (chain.last().second != chain.first().first) throw AssertionError("chain $chain is not closed")
}

private fun assertNoChain(input: List<Pair<Int, Int>>) {
    val chain = findChain(input)
    if (chain != null) throw AssertionError("expected null for $input but got $chain")
}

private fun d(a: Int, b: Int) = Pair(a, b)

fun main() {
    test("empty input gives an empty chain") {
        val chain = findChain(emptyList())
        if (chain == null || chain.isNotEmpty()) throw AssertionError("expected an empty chain but got $chain")
    }
    test("a single double is a chain") { assertChain(listOf(d(4, 4))) }
    test("a single non-double has no chain") { assertNoChain(listOf(d(1, 2))) }
    test("two dominoes that join both ends") { assertChain(listOf(d(1, 2), d(2, 1))) }
    test("two dominoes that do not close") { assertNoChain(listOf(d(1, 2), d(2, 3))) }
    test("three dominoes in a ring, shuffled and flipped") {
        assertChain(listOf(d(1, 2), d(3, 1), d(2, 3)))
        assertChain(listOf(d(2, 1), d(2, 3), d(1, 3)))
    }
    test("a ring with a repeated number") {
        assertChain(listOf(d(1, 1), d(2, 2), d(1, 2), d(2, 1)))
    }
    test("disconnected dominoes have no chain") {
        assertNoChain(listOf(d(1, 1), d(2, 2)))
        assertNoChain(listOf(d(1, 2), d(2, 1), d(3, 4), d(4, 3)))
    }
    test("a dead end that looks like a chain has none") {
        assertNoChain(listOf(d(1, 2), d(2, 3), d(3, 1), d(1, 4)))
    }
    test("odd count of a number rules the chain out") {
        assertNoChain(listOf(d(1, 2), d(2, 3), d(3, 4), d(4, 1), d(1, 3)))
    }
    test("a longer ring needs backtracking") {
        assertChain(listOf(d(1, 2), d(2, 3), d(3, 1), d(1, 4), d(4, 5), d(5, 1), d(1, 6), d(6, 1)))
    }
    test("eight dominoes over a few numbers") {
        assertChain(listOf(d(0, 1), d(1, 2), d(2, 0), d(0, 3), d(3, 3), d(3, 0), d(2, 2), d(1, 1)))
    }
    test("eight dominoes with one stray domino") {
        assertNoChain(listOf(d(0, 1), d(1, 2), d(2, 0), d(0, 3), d(3, 3), d(3, 0), d(2, 2), d(5, 6)))
    }
    test("the chain must start with the first input domino") {
        val input = listOf(d(5, 6), d(6, 7), d(7, 5))
        assertChain(input)
        val chain = findChain(input)!!
        if (norm(chain[0]) != d(5, 6)) throw AssertionError("chain starts with ${chain[0]}")
    }
    test("a first domino outside the only ring gives null") {
        assertNoChain(listOf(d(9, 9), d(1, 2), d(2, 3), d(3, 1)))
    }
    test("duplicate dominoes are all used") {
        assertChain(listOf(d(1, 2), d(1, 2), d(2, 1), d(2, 1)))
        assertNoChain(listOf(d(1, 2), d(1, 2), d(2, 1)))
    }
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    if (failures > 0) exitProcess(1)
}
