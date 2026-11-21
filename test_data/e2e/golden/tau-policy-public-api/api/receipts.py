"""Public receipt rendering."""

from core import orders
from core.money_format import format_minor_for


def render_receipt(order_id):
    """Render a plain-text receipt for a registered order."""
    order = orders.get_order(order_id)
    lines = ["RECEIPT %s" % order.order_id]
    for line in order.lines:
        amount = format_minor_for(order.currency, line.unit_minor * line.qty)
        lines.append("%-12s x%-3d %12s %s" % (line.name, line.qty, amount, order.currency))
    lines.append("TOTAL %s %s" % (format_minor_for(order.currency, orders.total_minor(order)), order.currency))
    return "\n".join(lines)
