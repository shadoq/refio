object Vlq {
    fun encode(values: List<UInt>): List<UByte> {
        val out = ArrayList<UByte>()
        for (value in values) {
            val groups = ArrayList<Int>()
            var rest = value
            do {
                groups.add((rest and 0x7Fu).toInt())
                rest = rest shr 7
            } while (rest != 0u)
            groups.reverse()
            for ((i, g) in groups.withIndex()) {
                out.add((if (i < groups.size - 1) g or 0x80 else g).toUByte())
            }
        }
        return out
    }

    fun decode(bytes: List<UByte>): List<UInt> {
        val out = ArrayList<UInt>()
        var acc = 0L
        var count = 0
        for (b in bytes) {
            val raw = b.toInt()
            acc = (acc shl 7) or (raw and 0x7F).toLong()
            count++
            if (raw and 0x80 == 0) {
                require(acc <= 0xFFFFFFFFL) { "too large" }
                out.add(acc.toUInt())
                acc = 0
                count = 0
            }
        }
        require(count == 0) { "incomplete sequence" }
        return out
    }
}
