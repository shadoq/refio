package math

/** Greatest common divisor via the Euclidean algorithm. This is already correct. */
fun gcd(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) {
        val t = y
        y = x % y
        x = t
    }
    return if (x < 0) -x else x
}
