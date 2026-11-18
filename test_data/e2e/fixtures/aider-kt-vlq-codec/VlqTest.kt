import kotlin.system.exitProcess

private var failures = 0

private fun test(name: String, body: () -> Unit) {
    try {
        body()
        println("ok   $name")
    } catch (t: Throwable) {
        failures++
        println("FAIL $name: $t")
    }
}

private fun <T> eq(actual: T, expected: T) {
    if (actual != expected) throw AssertionError("expected $expected but was $actual")
}

private fun expectIllegal(body: () -> Unit) {
    try {
        body()
    } catch (e: IllegalArgumentException) {
        return
    }
    throw AssertionError("expected IllegalArgumentException")
}

private fun bytes(vararg v: Int): List<UByte> = v.map { it.toUByte() }

private fun uints(vararg v: Long): List<UInt> = v.map { it.toUInt() }

fun main() {
    test("empty list encodes to no bytes") { eq(Vlq.encode(emptyList()), emptyList()) }
    test("empty stream decodes to no values") { eq(Vlq.decode(emptyList()), emptyList()) }
    test("zero is a single zero byte") { eq(Vlq.encode(uints(0)), bytes(0x00)) }
    test("127 fits in one byte") { eq(Vlq.encode(uints(127)), bytes(0x7F)) }
    test("128 is low group first") { eq(Vlq.encode(uints(128)), bytes(0x80, 0x01)) }
    test("300 is AC 02") { eq(Vlq.encode(uints(300)), bytes(0xAC, 0x02)) }
    test("16384 takes three bytes") { eq(Vlq.encode(uints(16384)), bytes(0x80, 0x80, 0x01)) }
    test("largest value takes five bytes") {
        eq(Vlq.encode(uints(0xFFFFFFFFL)), bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0F))
    }
    test("a list is the concatenation of its values") {
        eq(Vlq.encode(uints(1, 128, 300)), bytes(0x01, 0x80, 0x01, 0xAC, 0x02))
    }
    test("decode reads the same examples back") {
        eq(Vlq.decode(bytes(0xAC, 0x02)), uints(300))
        eq(Vlq.decode(bytes(0x80, 0x80, 0x01)), uints(16384))
        eq(Vlq.decode(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0F)), uints(0xFFFFFFFFL))
    }
    test("decode splits a stream into several values") {
        eq(Vlq.decode(bytes(0x7F, 0x80, 0x01, 0x00, 0xAC, 0x02)), uints(127, 128, 0, 300))
    }
    test("round trip over the boundaries of every byte length") {
        val values = uints(
            0, 1, 127, 128, 255, 16383, 16384, 2097151, 2097152,
            268435455, 268435456, 4294967294L, 4294967295L,
        )
        eq(Vlq.decode(Vlq.encode(values)), values)
        eq(Vlq.encode(values).size, 1 + 1 + 1 + 2 + 2 + 2 + 3 + 3 + 4 + 4 + 5 + 5 + 5)
    }
    test("an incomplete last value is rejected") {
        expectIllegal { Vlq.decode(bytes(0x80)) }
        expectIllegal { Vlq.decode(bytes(0xFF, 0xFF)) }
        expectIllegal { Vlq.decode(bytes(0x05, 0x80)) }
    }
    test("a value above 32 bits is rejected") {
        expectIllegal { Vlq.decode(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x10)) }
        expectIllegal { Vlq.decode(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x1F)) }
    }
    test("a sixth byte is rejected") {
        expectIllegal { Vlq.decode(bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x01)) }
    }
    test("a non-minimal value is rejected") {
        expectIllegal { Vlq.decode(bytes(0x80, 0x00)) }
        expectIllegal { Vlq.decode(bytes(0x81, 0x80, 0x00)) }
    }
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    if (failures > 0) exitProcess(1)
}
