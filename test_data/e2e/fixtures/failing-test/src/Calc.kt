// BUG: add is implemented as subtraction, so the checks in Main.kt fail at runtime.
fun add(a: Int, b: Int): Int = a - b
