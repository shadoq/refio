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

private fun <T> eq(actual: T, expected: T) {
    if (actual != expected) throw AssertionError("expected $expected but was $actual")
}

private fun expectIllegal(body: () -> Unit) {
    try {
        body()
    } catch (e: IllegalArgumentException) {
        return
    }
    throw AssertionError("expected IllegalArgumentException")
}

private fun f(i: Int) = Move(Op.FILL, i)
private fun e(i: Int) = Move(Op.EMPTY, i)
private fun p(i: Int, j: Int) = Move(Op.POUR, i, j)

/** Replays the moves, failing on an illegal or no-op move, and checks that the goal is reached. */
private fun replay(capacities: List<Int>, target: Int, moves: List<Move>) {
    val water = intArrayOf(0, 0, 0)
    for (m in moves) {
        when (m.op) {
            Op.FILL -> {
                if (water[m.jug] == capacities[m.jug]) throw AssertionError("no-op $m")
                water[m.jug] = capacities[m.jug]
            }
            Op.EMPTY -> {
                if (water[m.jug] == 0) throw AssertionError("no-op $m")
                water[m.jug] = 0
            }
            Op.POUR -> {
                if (m.jug == m.into) throw AssertionError("pour into itself $m")
                val amount = minOf(water[m.jug], capacities[m.into] - water[m.into])
                if (amount == 0) throw AssertionError("no-op $m")
                water[m.jug] -= amount
                water[m.into] += amount
            }
        }
    }
    if (target !in water) throw AssertionError("no jug holds $target after $moves: ${water.toList()}")
}

private fun shortest(capacities: List<Int>, target: Int, length: Int) {
    val moves = measure(capacities, target) ?: throw AssertionError("expected a solution for $capacities / $target")
    eq(moves.size, length)
    replay(capacities, target, moves)
}

fun main() {
    test("target zero needs no moves") { eq(measure(listOf(3, 5, 8), 0), emptyList()) }
    test("a full jug is one move") { eq(measure(listOf(3, 5, 8), 3), listOf(f(0))) }
    test("overflowing into a smaller jug leaves the remainder") {
        eq(measure(listOf(2, 5, 9), 7), listOf(f(2), p(2, 0)))
    }
    test("exact sequence with ties between equal-length plans") {
        eq(measure(listOf(4, 9, 11), 6), listOf(f(0), f(2), p(0, 1), p(2, 1)))
    }
    test("exact sequence of the classic 3-5 measurement") {
        eq(measure(listOf(3, 5, 8), 4), listOf(f(1), p(1, 0), e(0), p(1, 0), f(1), p(1, 0)))
    }
    test("exact sequence for scaled jugs") {
        eq(measure(listOf(6, 10, 15), 8), listOf(f(1), p(1, 0), e(0), p(1, 0), f(1), p(1, 0)))
    }
    test("long plans are still the shortest ones") {
        shortest(listOf(5, 7, 12), 1, 8)
        shortest(listOf(5, 7, 12), 3, 4)
    }
    test("a target that is not a multiple of the common divisor is impossible") {
        eq(measure(listOf(4, 6, 8), 5), null)
        eq(measure(listOf(6, 10, 14), 5), null)
    }
    test("a target above every jug is impossible") {
        eq(measure(listOf(3, 5, 8), 9), null)
        eq(measure(listOf(3, 5, 8), 100), null)
    }
    test("invalid input is rejected") {
        expectIllegal { measure(listOf(3, 5), 1) }
        expectIllegal { measure(listOf(3, 5, 8, 9), 1) }
        expectIllegal { measure(listOf(3, 0, 8), 1) }
        expectIllegal { measure(listOf(3, -5, 8), 1) }
        expectIllegal { measure(listOf(3, 5, 8), -1) }
    }
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    if (failures > 0) exitProcess(1)
}
