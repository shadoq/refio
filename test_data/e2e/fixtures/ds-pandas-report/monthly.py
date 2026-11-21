"""Monthly sales report."""
import pandas as pd


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
    df["month"] = pd.to_datetime(df["ts"]).dt.strftime("%Y-%m")
    df["amount"] = df["amount"].fillna(0)
    out = (
        df.groupby(["month", "store"])
        .agg(orders=("amount", "count"), revenue=("amount", "sum"), avg_ticket=("amount", "mean"))
        .round(2)
        .reset_index()
    )
    return out
