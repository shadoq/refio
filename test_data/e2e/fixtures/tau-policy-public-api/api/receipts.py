"""Public receipt rendering."""

from api.money import format_minor
from core import orders


def render_receipt(order_id):
    """Render a plain-text receipt for a registered order."""
    order = orders.get_order(order_id)
    lines = ["RECEIPT %s" % order.order_id]
    for line in order.lines:
        lines.append("%-12s x%-3d %12s %s" % (
            line.name, line.qty, format_minor(line.unit_minor * line.qty), order.currency))
    lines.append("TOTAL %s %s" % (format_minor(orders.total_minor(order)), order.currency))
    return "\n".join(lines)
