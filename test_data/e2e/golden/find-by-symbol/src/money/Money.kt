package money

/** Formats a cents amount as a dollar string. */
fun formatMoney(cents: Int): String {
    val sign = if (cents < 0) "-" else ""
    val abs = Math.abs(cents.toLong())
    return sign + "$" + (abs / 100) + "." + (abs % 100).toString().padStart(2, '0')
}
