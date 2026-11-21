fun findChain(dominoes: List<Pair<Int, Int>>): List<Pair<Int, Int>>? {
    if (dominoes.isEmpty()) return emptyList()
    val first = dominoes[0]
    val rest = dominoes.drop(1)
    val used = BooleanArray(rest.size)
    val chain = ArrayList<Pair<Int, Int>>()

    fun extend(start: Int, end: Int, left: Int): Boolean {
        if (left == 0) return true
        for (i in rest.indices) {
            if (used[i]) continue
            val d = rest[i]
            for (flip in listOf(false, true)) {
                val a = if (flip) d.second else d.first
                val b = if (flip) d.first else d.second
                if (a != end) continue
                used[i] = true
                chain.add(Pair(a, b))
                if (extend(start, b, left - 1)) return true
                chain.removeAt(chain.size - 1)
                used[i] = false
                if (d.first == d.second) break
            }
        }
        return false
    }

    for (flip in listOf(false, true)) {
        val a = if (flip) first.second else first.first
        val b = if (flip) first.first else first.second
        chain.clear()
        chain.add(Pair(a, b))
        if (extend(a, b, rest.size)) return ArrayList(chain)
        if (first.first == first.second) break
    }
    return null
}
