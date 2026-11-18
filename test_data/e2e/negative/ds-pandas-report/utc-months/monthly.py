"""Monthly sales report."""
from decimal import Decimal, ROUND_HALF_UP

import pandas as pd

CENT = Decimal("0.01")


def _half_up(value):
    return float(value.quantize(CENT, rounding=ROUND_HALF_UP))


def monthly_report(events):
    """Summarise sales events per local month and store.

    `events` has the columns ts (ISO 8601 text, always UTC, e.g. 2025-03-31T22:30:00Z),
    store, amount (may be missing) and qty. A month is the calendar month in the
    Europe/Warsaw time zone, not in UTC, and it is written as YYYY-MM.

    Result columns, in this order:
      month, store, orders, revenue, avg_ticket
    - orders: number of events, including those without an amount (int)
    - revenue: sum of the known amounts, rounded half up to 2 decimals (float);
      0.0 when no event has an amount
    - avg_ticket: exact sum of the known amounts divided by the number of events that
      have an amount, rounded half up to 2 decimals (float); NaN when none has one

    Half up means 0.015 -> 0.02 and 1.005 -> 1.01: amounts are decimal figures, so judge
    them by their written value, not by their binary float approximation. Rows are sorted
    by month, then store; combinations without events do not appear. The input is not modified.
    """
    df = events.copy()
    local = pd.to_datetime(df["ts"], utc=True)
    df["month"] = local.dt.strftime("%Y-%m")
    rows = []
    for (month, store), group in df.groupby(["month", "store"], sort=True):
        amounts = [Decimal(str(a)) for a in group["amount"].dropna()]
        total = sum(amounts, Decimal(0))
        avg = _half_up(total / len(amounts)) if amounts else float("nan")
        rows.append({
            "month": month,
            "store": store,
            "orders": len(group),
            "revenue": _half_up(total),
            "avg_ticket": avg,
        })
    out = pd.DataFrame(rows, columns=["month", "store", "orders", "revenue", "avg_ticket"])
    return out.astype({"orders": "int64", "revenue": "float64", "avg_ticket": "float64"})
