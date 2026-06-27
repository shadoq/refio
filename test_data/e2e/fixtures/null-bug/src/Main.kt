// e2e fixture (docs/0061): a deliberate null-safety bug for the agent to find and fix.
// `describe` dereferences its nullable parameter without a guard, so a null argument throws
// at runtime. A correct, minimal fix guards the parameter before dereferencing it.

fun describe(x: String?): String {
    return "length=" + x.length
}

fun main() {
    println(describe("hello"))
    println(describe(null))
}
