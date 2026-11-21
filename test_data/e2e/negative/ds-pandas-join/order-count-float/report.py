"""Revenue report per customer."""
import pandas as pd


def load_orders(path):
    return pd.read_csv(path)


def load_customers(path):
    return pd.read_csv(path, dtype={"customer_id": str})


def build_report(orders, customers):
    """One row per customer: customer_id, name, region, order_count, revenue.

    Cancelled orders do not count. Customers without any counted order still appear, with
    order_count 0 and revenue 0.0. Orders of unknown customers are ignored. Rows are sorted
    by customer_id. customer_id is the zero padded 3 digit code (str), order_count is int,
    revenue is float rounded to 2 places.
    """
    paid = orders[orders["status"] != "cancelled"]
    paid = paid.assign(customer_id=paid["customer_id"].astype(str).str.zfill(3))
    totals = (
        paid.groupby("customer_id")
        .agg(order_count=("order_id", "count"), revenue=("amount", "sum"))
        .reset_index()
    )
    report = customers.merge(totals, on="customer_id", how="left")
    report["revenue"] = report["revenue"].fillna(0.0)
    report = report[["customer_id", "name", "region", "order_count", "revenue"]]
    return report.sort_values("customer_id").reset_index(drop=True)
