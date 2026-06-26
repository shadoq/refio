package app

// `normalize` is supposed to clamp its input to the inclusive range [0, 100], but right now it
// returns the raw score, so out-of-range values leak through. There is no clamp helper yet.
fun normalize(score: Int): Int {
    return score
}

fun main() {
    println(normalize(150))   // expected: 100
    println(normalize(-20))   // expected: 0
    println(normalize(42))    // expected: 42
}
