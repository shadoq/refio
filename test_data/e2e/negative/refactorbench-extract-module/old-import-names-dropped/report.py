from datecalc import business_days_between, days_between

LATE_FEE_PER_BUSINESS_DAY_CENTS = 150
FREE_GRACE_BUSINESS_DAYS = 2


def format_invoice_line(invoice):
    amount = "%d.%02d" % divmod(invoice["amount_cents"], 100)
    return "%-8s %-10s %9s" % (invoice["id"], invoice["due"], amount)


def overdue_invoices(invoices, today):
    return [inv for inv in invoices if days_between(inv["due"], today) > 0 and not inv.get("paid")]


def late_fee(invoice, today):
    if invoice.get("paid") or days_between(invoice["due"], today) <= 0:
        return 0
    chargeable = business_days_between(invoice["due"], today) - FREE_GRACE_BUSINESS_DAYS
    return max(0, chargeable) * LATE_FEE_PER_BUSINESS_DAY_CENTS


def summary(invoices, today):
    late = overdue_invoices(invoices, today)
    fees = sum(late_fee(inv, today) for inv in late)
    return {"overdue": [inv["id"] for inv in late], "fees_cents": fees}
