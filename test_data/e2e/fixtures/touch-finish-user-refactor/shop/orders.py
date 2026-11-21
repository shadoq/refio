"""Orders placed by clients."""

from collections import namedtuple

from shop.clients import find_client

Order = namedtuple("Order", "order_id client_id items")

_ORDERS = []


def place_order(order_id, client_id, items):
    """items: list of (sku, unit_cents, qty). The client must be registered."""
    find_client(client_id)
    order = Order(order_id, client_id, list(items))
    _ORDERS.append(order)
    return order


def order_total(order):
    return sum(unit * qty for _, unit, qty in order.items)


def orders_of_client(client_id):
    return [order for order in _ORDERS if order.client_id == client_id]


def all_orders():
    return list(_ORDERS)


def clear_orders():
    _ORDERS.clear()
