"""Invoice text for an order."""

from shop.clients import find_customer
from shop.orders import order_total

DISCOUNT_BY_TIER = {"standard": 0, "gold": 5, "vip": 10}


def customer_discount_percent(customer):
    """Discount in percent for the tier of a customer."""
    return DISCOUNT_BY_TIER.get(customer.tier, 0)


def build_invoice(order):
    """Plain-text invoice for one order, addressed to its customer."""
    customer = find_customer(order.customer_id)
    total = order_total(order)
    discount = customer_discount_percent(customer)
    due = total - total * discount // 100
    lines = ["INVOICE %s" % order.order_id, "Customer: %s <%s>" % (customer.name, customer.email)]
    for sku, unit, qty in order.items:
        lines.append("  %s x%d @ %d" % (sku, qty, unit))
    lines.append("Customer discount: %d%%" % discount)
    lines.append("DUE %d" % due)
    return "\n".join(lines)
