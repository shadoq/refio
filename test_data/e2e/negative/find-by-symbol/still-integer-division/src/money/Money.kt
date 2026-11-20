package money

/** Formats a cents amount as a dollar string. */
fun formatMoney(cents: Int): String {
    return "$" + (cents / 100) + ".00"
}
