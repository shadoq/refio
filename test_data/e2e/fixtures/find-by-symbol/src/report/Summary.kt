package report

import money.formatMoney

fun revenueLine(cents: Int): String = "Revenue: " + formatMoney(cents)
