enum class Op { FILL, EMPTY, POUR }

data class Move(val op: Op, val jug: Int, val into: Int = -1)

fun measure(capacities: List<Int>, target: Int): List<Move>? {
    TODO("not implemented")
}
