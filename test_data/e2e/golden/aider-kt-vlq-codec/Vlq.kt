object Vlq {
    fun encode(values: List<UInt>): List<UByte> {
        val out = ArrayList<UByte>()
        for (value in values) {
            var rest = value
            while (true) {
                val low = rest and 0x7Fu
                rest = rest shr 7
                if (rest == 0u) {
                    out.add(low.toUByte())
                    break
                }
                out.add((low or 0x80u).toUByte())
            }
        }
        return out
    }

    fun decode(bytes: List<UByte>): List<UInt> {
        val out = ArrayList<UInt>()
        var acc = 0L
        var groups = 0
        for (b in bytes) {
            val raw = b.toInt()
            val group = raw and 0x7F
            val more = raw and 0x80 != 0
            require(groups < 5) { "value is longer than five bytes" }
            require(groups != 4 || group <= 0x0F) { "value does not fit in 32 bits" }
            acc = acc or (group.toLong() shl (7 * groups))
            groups++
            if (!more) {
                require(groups == 1 || group != 0) { "value is not minimal" }
                out.add(acc.toUInt())
                acc = 0
                groups = 0
            }
        }
        require(groups == 0) { "incomplete sequence" }
        return out
    }
}
