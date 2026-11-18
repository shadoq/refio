"""In-memory order store."""

from collections import namedtuple

Line = namedtuple("Line", "name unit_minor qty")
Order = namedtuple("Order", "order_id currency lines")

_ORDERS = {}


def register(order):
    _ORDERS[order.order_id] = order


def get_order(order_id):
    try:
        return _ORDERS[order_id]
    except KeyError:
        raise KeyError("unknown order: %s" % order_id)


def clear():
    _ORDERS.clear()


def total_minor(order):
    return sum(line.unit_minor * line.qty for line in order.lines)
