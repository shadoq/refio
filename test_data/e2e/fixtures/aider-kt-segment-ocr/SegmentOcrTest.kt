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
    if (actual != expected) throw AssertionError("expected <$expected> but was <$actual>")
}

private fun bad(lines: List<String>) {
    try {
        readDigits(lines)
    } catch (e: IllegalArgumentException) {
        return
    }
    throw AssertionError("expected IllegalArgumentException for $lines")
}

fun main() {
    test("all ten digits in one row") {
        eq(readDigits(listOf(" _     _  _     _  _  _  _  _ ", "| |  | _| _||_||_ |_   ||_||_|", "|_|  ||_  _|  | _||_|  ||_| _|", "                              ")), "0123456789")
    }
    test("each digit on its own") {
        eq(readDigits(listOf(" _ ", "| |", "|_|", "   ")), "0")
        eq(readDigits(listOf("   ", "  |", "  |", "   ")), "1")
        eq(readDigits(listOf(" _ ", " _|", "|_ ", "   ")), "2")
        eq(readDigits(listOf(" _ ", " _|", " _|", "   ")), "3")
        eq(readDigits(listOf("   ", "|_|", "  |", "   ")), "4")
        eq(readDigits(listOf(" _ ", "|_ ", " _|", "   ")), "5")
        eq(readDigits(listOf(" _ ", "|_ ", "|_|", "   ")), "6")
        eq(readDigits(listOf(" _ ", "  |", "  |", "   ")), "7")
        eq(readDigits(listOf(" _ ", "|_|", "|_|", "   ")), "8")
        eq(readDigits(listOf(" _ ", "|_|", " _|", "   ")), "9")
    }
    test("right-trimmed lines are padded back to the grid") {
        eq(readDigits(listOf(" _     _  _     _  _  _  _  _", "| |  | _| _||_||_ |_   ||_||_|", "|_|  ||_  _|  | _||_|  ||_| _|", "")), "0123456789")
        eq(readDigits(listOf("", "  |  |  |", "  |  |  |", "")), "111")
        eq(readDigits(listOf(" _", "  |", "  |", "")), "7")
        eq(readDigits(listOf("    _  _", "  || |  |  |", "  ||_|  |  |", "")), "1071")
    }
    test("several rows are joined with commas") {
        eq(readDigits(listOf(
            "    _  _ ",
            "  | _| _|",
            "  ||_  _|",
            "         ",
            "    _  _ ",
            "|_||_ |_ ",
            "  | _||_|",
            "         ",
            " _  _  _ ",
            "  ||_||_|",
            "  ||_| _|",
            "         ",
        )), "123,456,789")
    }
    test("a trimmed row among full rows") {
        eq(readDigits(listOf(
            " _  _ ",
            "|_|| |",
            " _||_|",
            "      ",
            "",
            "  |  |",
            "  |  |",
            "",
            " _  _ ",
            " _||_|",
            " _||_|",
            "      ",
        )), "90,11,38")
    }
    test("a blank cell reads as a space") {
        eq(readDigits(listOf("       _ ", "  |    _|", "  |   |_ ", "         ")), "1 2")
    }
    test("a shorter row ends with blank cells") {
        eq(readDigits(listOf(
            "    _  _ ",
            "  | _| _|",
            "  ||_  _|",
            "         ",
            " _",
            "  |",
            "  |",
            "",
        )), "123,7  ")
    }
    test("an unknown shape reads as a question mark") {
        eq(readDigits(listOf("    _ ", "  ||||", "  ||_ ", "      ")), "1?")
        eq(readDigits(listOf("   ___ _ ", "  | _| _|", "  ||_  _|", "         ")), "1?3")
        eq(readDigits(listOf("    _ ", "|_||_ ", "  | _|", "    _ ")), "4?")
    }
    test("an unreadable cell does not stop the other digits or rows") {
        eq(readDigits(listOf(
            "   ___ _ ",
            "  | _| _|",
            "  ||_  _|",
            "         ",
            " _ ",
            "|_ ",
            " _|",
            "   ",
        )), "1?3,5  ")
    }
    test("empty input has no rows") {
        eq(readDigits(emptyList()), "")
    }
    test("a wrong number of lines is an error") {
        bad(listOf("    _ ", "  | _|", "  ||_ "))
        bad(listOf("    _ ", "  | _|", "  ||_ ", "      ", "   "))
        bad(listOf("    _ ", "  | _|", "  ||_ ", "      ", " _ ", " _|"))
    }
    test("a width that is not a multiple of three is an error") {
        bad(listOf(" _  ", "| | ", "|_| ", "    "))
        bad(listOf(" _ ", "| |", "|_|  ", "   "))
        bad(listOf(" _ _", "| | ", "|_| ", ""))
    }
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    if (failures > 0) exitProcess(1)
}
