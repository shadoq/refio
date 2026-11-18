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

private fun bad(text: String) {
    try {
        parseRecord(text)
    } catch (e: IllegalArgumentException) {
        return
    }
    throw AssertionError("expected IllegalArgumentException for <$text>")
}

private fun node(vararg props: Pair<String, List<String>>, children: List<Node> = emptyList()) =
    Node(props.toMap(), children)

private fun v(vararg values: String) = values.toList()

fun main() {
    test("an empty node") { eq(parseRecord("(;)"), Node()) }
    test("one property") { eq(parseRecord("(;A[x])"), node("A" to v("x"))) }
    test("the example with two variations") {
        eq(
            parseRecord("(;A[x]B[y](;C[z])(;D[w]))"),
            node(
                "A" to v("x"), "B" to v("y"),
                children = listOf(node("C" to v("z")), node("D" to v("w"))),
            ),
        )
    }
    test("a chain of nodes nests one inside the other") {
        eq(
            parseRecord("(;A[1];B[2];C[3])"),
            node("A" to v("1"), children = listOf(node("B" to v("2"), children = listOf(node("C" to v("3")))))),
        )
    }
    test("variations attach to the last node of the chain") {
        eq(
            parseRecord("(;A[1];B[2](;C[3])(;D[4]))"),
            node(
                "A" to v("1"),
                children = listOf(node("B" to v("2"), children = listOf(node("C" to v("3")), node("D" to v("4"))))),
            ),
        )
    }
    test("variations nest and keep their order") {
        eq(
            parseRecord("(;A[1](;B[2](;C[3])(;D[4]))(;E[5]))"),
            node(
                "A" to v("1"),
                children = listOf(
                    node("B" to v("2"), children = listOf(node("C" to v("3")), node("D" to v("4")))),
                    node("E" to v("5")),
                ),
            ),
        )
    }
    test("a variation can itself be a chain") {
        eq(
            parseRecord("(;R[0](;A[1];B[2])(;C[3]))"),
            node(
                "R" to v("0"),
                children = listOf(
                    node("A" to v("1"), children = listOf(node("B" to v("2")))),
                    node("C" to v("3")),
                ),
            ),
        )
    }
    test("a property can have several values and an empty value") {
        eq(parseRecord("(;A[1][2][3]B[])"), node("A" to v("1", "2", "3"), "B" to v("")))
    }
    test("keys can have several letters") {
        eq(parseRecord("(;AB[1]CD[2])"), node("AB" to v("1"), "CD" to v("2")))
    }
    test("backslash escapes the next character") {
        eq(parseRecord("(;A[a\\]b\\\\c])"), node("A" to v("a]b\\c")))
        eq(parseRecord("(;A[\\x\\;])"), node("A" to v("x;")))
    }
    test("structure characters inside a value are plain text") {
        eq(parseRecord("(;A[(;B[x);y])"), node("A" to v("(;B[x);y")))
    }
    test("whitespace inside a value is kept, between tokens it is ignored") {
        eq(parseRecord("(;A[ x\n y ])"), node("A" to v(" x\n y ")))
        eq(
            parseRecord(" \n( ;A [x]\n [y]\tB[z] ( ;C[1] ) ) \n"),
            node("A" to v("x", "y"), "B" to v("z"), children = listOf(node("C" to v("1")))),
        )
    }
    test("malformed records are rejected") {
        bad("")
        bad("   ")
        bad("()")
        bad(";A[x]")
        bad("(A[x])")
        bad("(;A[x]")
        bad("(;A[x]))")
        bad("(;A)")
        bad("(;a[x])")
        bad("(;A1[x])")
        bad("(;A[x)")
        bad("(;A[x\\])")
        bad("(;A[x]A[y])")
        bad("(;A[x](B[y]))")
        bad("(;A[x](;B[y]);C[z])")
        bad("(;A[x])(;B[y])")
        bad("(;A[x]) trailing")
    }
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    if (failures > 0) exitProcess(1)
}
