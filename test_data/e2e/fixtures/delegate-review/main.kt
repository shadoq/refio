import validate.isAdult

fun main() {
    check(!isAdult(17)) { "isAdult(17) must be false" }
    check(isAdult(18)) { "isAdult(18) must be true - 18 is an adult" }
    check(isAdult(19)) { "isAdult(19) must be true" }
    check(isAdult(120)) { "isAdult(120) must be true - 120 is the inclusive upper bound" }
    check(!isAdult(121)) { "isAdult(121) must be false" }
    println("OK")
}
