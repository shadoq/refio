enum class Op { FILL, EMPTY, POUR }

data class Move(val op: Op, val jug: Int, val into: Int = -1)

fun measure(capacities: List<Int>, target: Int): List<Move>? {
    require(capacities.size == 3) { "exactly three jugs are required" }
    require(capacities.all { it > 0 }) { "capacities must be positive" }
    require(target >= 0) { "target must not be negative" }

    val moves = ArrayList<Move>()
    for (i in 0..2) for (j in 0..2) if (i != j) moves.add(Move(Op.POUR, i, j))
    for (i in 0..2) moves.add(Move(Op.FILL, i))
    for (i in 0..2) moves.add(Move(Op.EMPTY, i))

    val start = listOf(0, 0, 0)
    if (target in start) return emptyList()

    val parent = HashMap<List<Int>, Pair<List<Int>, Move>>()
    val seen = HashSet<List<Int>>()
    seen.add(start)
    val queue = ArrayDeque<List<Int>>()
    queue.add(start)
    while (queue.isNotEmpty()) {
        val state = queue.removeFirst()
        for (m in moves) {
            val next = state.toMutableList()
            when (m.op) {
                Op.FILL -> {
                    if (next[m.jug] == capacities[m.jug]) continue
                    next[m.jug] = capacities[m.jug]
                }
                Op.EMPTY -> {
                    if (next[m.jug] == 0) continue
                    next[m.jug] = 0
                }
                Op.POUR -> {
                    val amount = minOf(next[m.jug], capacities[m.into] - next[m.into])
                    if (amount == 0) continue
                    next[m.jug] -= amount
                    next[m.into] += amount
                }
            }
            if (!seen.add(next)) continue
            parent[next] = Pair(state, m)
            if (target in next) {
                val path = ArrayList<Move>()
                var cur: List<Int> = next
                while (cur != start) {
                    val (prev, move) = parent.getValue(cur)
                    path.add(move)
                    cur = prev
                }
                path.reverse()
                return path
            }
            queue.add(next)
        }
    }
    return null
}
