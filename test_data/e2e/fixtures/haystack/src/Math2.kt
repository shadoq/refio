package app

object Math2 {
    fun clampInt(v: Int, lo: Int, hi: Int): Int = maxOf(lo, minOf(hi, v))
}
