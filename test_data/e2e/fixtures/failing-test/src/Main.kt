// Executable test: each check() throws if add() is wrong, so the program exits non-zero
// until add is fixed. Do NOT change this file — fix the production code in Calc.kt.
fun main() {
    check(add(2, 3) == 5) { "add(2,3) should be 5" }
    check(add(10, 4) == 14) { "add(10,4) should be 14" }
    check(add(0, 0) == 0) { "add(0,0) should be 0" }
    println("OK")
}
