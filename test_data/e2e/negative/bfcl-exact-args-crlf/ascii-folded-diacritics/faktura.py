# -*- coding: utf-8 -*-
"""Wiersze faktury VAT: wartosci netto, VAT i brutto."""

from decimal import Decimal, ROUND_HALF_EVEN, ROUND_HALF_UP

VAT_RATES = {
	"23": Decimal("0.23"),
	"8": Decimal("0.08"),
	"5": Decimal("0.05"),
	"zw": Decimal("0"),
}

LABELS = {
	"net": "Wartosc netto",
	"vat": "Kwota VAT",
	"gross": "Wartosc brutto",
	"exempt": "zwolniony z podatku",
}

CURRENCY = "zl"


def money(value):
	"""Round to full grosze."""
	return value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def percent(value):
	"""Whole percent label, e.g. Decimal("0.23") -> "23%"."""
	return "%d%%" % int((value * 100).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def comma(value):
	return str(value).replace(".", ",")


def line_totals(unit_net, qty, rate_code):
	"""Return (net, vat, gross) of one invoice line."""
	if rate_code not in VAT_RATES:
		raise ValueError("Nieprawidlowa stawka VAT: %s" % rate_code)
	net = money(Decimal(unit_net) * qty)
	vat = money(net * VAT_RATES[rate_code])
	return net, vat, net + vat


def describe_line(unit_net, qty, rate_code):
	net, vat, gross = line_totals(unit_net, qty, rate_code)
	parts = [
		"%s: %s %s" % (LABELS["net"], comma(net), CURRENCY),
		"%s: %s %s" % (LABELS["vat"], comma(vat), CURRENCY),
		"%s: %s %s" % (LABELS["gross"], comma(gross), CURRENCY),
	]
	if rate_code == "zw":
		parts.append(LABELS["exempt"])
	else:
		parts.append("stawka %s" % percent(VAT_RATES[rate_code]))
	return " | ".join(parts)


def invoice_total(lines):
	"""Sum of gross values for (unit_net, qty, rate_code) lines."""
	total = Decimal("0")
	for unit_net, qty, rate_code in lines:
		total += line_totals(unit_net, qty, rate_code)[2]
	return total
