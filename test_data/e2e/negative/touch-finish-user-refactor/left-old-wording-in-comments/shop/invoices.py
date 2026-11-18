"""Invoice text for an order."""

from shop.clients import find_client
from shop.orders import order_total

DISCOUNT_BY_TIER = {"standard": 0, "gold": 5, "vip": 10}


def client_discount_percent(client):
    """Discount in percent for the tier of a client."""
    return DISCOUNT_BY_TIER.get(client.tier, 0)


def build_invoice(order):
    """Plain-text invoice for one order, addressed to its customer."""
    client = find_client(order.client_id)
    total = order_total(order)
    discount = client_discount_percent(client)
    due = total - total * discount // 100
    lines = ["INVOICE %s" % order.order_id, "Client: %s <%s>" % (client.name, client.email)]
    for sku, unit, qty in order.items:
        lines.append("  %s x%d @ %d" % (sku, qty, unit))
    lines.append("Client discount: %d%%" % discount)
    lines.append("DUE %d" % due)
    return "\n".join(lines)
