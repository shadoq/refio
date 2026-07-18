object Calc {
    // TODO: implement add so it returns the sum of a and b
    fun add(a: Int, b: Int): Int = TODO("not implemented")
}

fun main() {
    val r = Calc.add(2, 3)
    if (r != 5) {
        System.err.println("FAIL: add(2,3)=$r (expected 5)")
        kotlin.system.exitProcess(1)
    }
    println("OK")
}
