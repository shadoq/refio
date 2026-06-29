package ui

import money.formatMoney

fun priceTag(cents: Int): String = formatMoney(cents)
