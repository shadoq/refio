"""Reports over clients and their orders."""

from shop.clients import find_client
from shop.orders import all_orders, order_total


def client_summary(client_id):
    """Order count and spend of one client."""
    mine = [order for order in all_orders() if order.client_id == client_id]
    return {
        "client_id": client_id,
        "orders": len(mine),
        "spend": sum(order_total(order) for order in mine),
    }


def top_clients(limit=3):
    """(client_id, spend) pairs, biggest spend first, ties by id."""
    spend = {}
    for order in all_orders():
        spend[order.client_id] = spend.get(order.client_id, 0) + order_total(order)
    ranked = sorted(spend.items(), key=lambda item: (-item[1], item[0]))
    return ranked[:limit]


def format_top_clients(limit=3):
    lines = ["TOP CLIENTS"]
    for rank, (client_id, spend) in enumerate(top_clients(limit), start=1):
        lines.append("%d. %s (%s) %d" % (rank, client_id, find_client(client_id).name, spend))
    return "\n".join(lines)
