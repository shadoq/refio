"""Reports over customers and their orders."""

from shop.clients import find_customer
from shop.orders import all_orders, order_total


def customer_summary(customer_id):
    """Order count and spend of one customer."""
    mine = [order for order in all_orders() if order.customer_id == customer_id]
    return {
        "customer_id": customer_id,
        "orders": len(mine),
        "spend": sum(order_total(order) for order in mine),
    }


def top_customers(limit=3):
    """(customer_id, spend) pairs, biggest spend first, ties by id."""
    spend = {}
    for order in all_orders():
        spend[order.customer_id] = spend.get(order.customer_id, 0) + order_total(order)
    ranked = sorted(spend.items(), key=lambda item: (-item[1], item[0]))
    return ranked[:limit]


def format_top_customers(limit=3):
    lines = ["TOP CUSTOMERS"]
    for rank, (customer_id, spend) in enumerate(top_customers(limit), start=1):
        lines.append("%d. %s (%s) %d" % (rank, customer_id, find_customer(customer_id).name, spend))
    return "\n".join(lines)
