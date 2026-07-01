package log

import money.formatMoney

fun auditAmount(cents: Int): String = "amount=" + formatMoney(cents)
