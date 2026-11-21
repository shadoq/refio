from datetime import date, timedelta

LATE_FEE_PER_BUSINESS_DAY_CENTS = 150
FREE_GRACE_BUSINESS_DAYS = 2


def parse_iso_date(text):
    """Parse YYYY-MM-DD, ignoring surrounding spaces."""
    return date.fromisoformat(text.strip())


def days_between(start, end):
    """Calendar days from start to end (negative when end is earlier)."""
    return (parse_iso_date(end) - parse_iso_date(start)).days


def is_weekend(day):
    """True for a Saturday or Sunday date object."""
    return day.weekday() >= 5


def business_days_between(start, end):
    """Number of Monday-Friday days in the half-open range [start, end)."""
    first = parse_iso_date(start)
    last = parse_iso_date(end)
    count = 0
    current = first
    while current < last:
        if not is_weekend(current):
            count += 1
        current += timedelta(days=1)
    return count


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
