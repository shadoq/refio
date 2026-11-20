package order

import money.formatMoney

fun cartTotalLine(cents: Int): String = "Total: " + formatMoney(cents)

// patched the caller instead of formatMoney
