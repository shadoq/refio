package order

import money.formatMoney

fun receiptLine(cents: Int): String = "Paid " + formatMoney(cents)
