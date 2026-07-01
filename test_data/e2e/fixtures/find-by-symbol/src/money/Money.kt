package money

/** Formats a cents amount as a dollar string. */
fun formatMoney(cents: Int): String {
    // BUG: integer division drops the cents, so 1099 renders as "$10" not "$10.99".
    return "$" + (cents / 100)
}
