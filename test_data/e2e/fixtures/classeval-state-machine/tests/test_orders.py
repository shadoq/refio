import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from orders import (GuardFailed, IllegalTransition, Order, OrderError,
                    REFUND_WINDOW_SECONDS)


class Clock:
    def __init__(self, now=1000):
        self.now = now

    def __call__(self):
        return self.now


def make(clock=None):
    clock = clock or Clock()
    return Order(clock), clock


def to_state(order, *events):
    for e in events:
        order.apply(e)
    return order


def delivered_order():
    order, clock = make()
    order.add_item("pen", 150)
    order.set_address("1 Main St")
    clock.now = 5000
    to_state(order, "pay", "pack", "ship", "deliver")
    return order, clock


class HappyPath(unittest.TestCase):
    def test_full_life_cycle_and_history(self):
        order, clock = make(Clock(100))
        order.add_item("pen", 150)
        order.add_item("ink", 250)
        order.set_address("1 Main St")
        self.assertEqual(order.state, "NEW")
        self.assertEqual(order.total_cents, 400)
        for t, event in ((110, "pay"), (120, "pack"), (130, "ship"), (140, "deliver")):
            clock.now = t
            order.apply(event)
        self.assertEqual(order.state, "DELIVERED")
        self.assertEqual(order.history, [
            ("NEW", "pay", "PAID", 110),
            ("PAID", "pack", "PACKED", 120),
            ("PACKED", "ship", "SHIPPED", 130),
            ("SHIPPED", "deliver", "DELIVERED", 140),
        ])

    def test_apply_returns_new_state(self):
        order, _ = make()
        order.add_item("pen", 1)
        self.assertEqual(order.apply("pay"), "PAID")

    def test_history_is_a_copy(self):
        order, _ = make()
        order.add_item("pen", 1)
        order.apply("pay")
        order.history.append(("x", "y", "z", 0))
        order.history.clear()
        self.assertEqual(len(order.history), 1)


class Cancel(unittest.TestCase):
    def test_cancel_new_order(self):
        order, _ = make()
        self.assertEqual(order.apply("cancel"), "CANCELLED")

    def test_cancel_paid_or_packed_refunds(self):
        order, _ = make()
        order.add_item("pen", 1)
        to_state(order, "pay")
        self.assertEqual(order.apply("cancel"), "REFUNDED")
        order2, _ = make()
        order2.add_item("pen", 1)
        order2.set_address("a")
        to_state(order2, "pay", "pack")
        self.assertEqual(order2.apply("cancel"), "REFUNDED")

    def test_cannot_cancel_after_shipping(self):
        order, _ = make()
        order.add_item("pen", 1)
        order.set_address("a")
        to_state(order, "pay", "pack", "ship")
        with self.assertRaises(IllegalTransition):
            order.apply("cancel")


class Guards(unittest.TestCase):
    def test_pay_needs_an_item(self):
        order, _ = make()
        with self.assertRaises(GuardFailed):
            order.apply("pay")
        self.assertEqual(order.state, "NEW")
        self.assertEqual(order.history, [])

    def test_ship_needs_an_address(self):
        order, _ = make()
        order.add_item("pen", 1)
        to_state(order, "pay", "pack")
        with self.assertRaises(GuardFailed):
            order.apply("ship")
        self.assertEqual(order.state, "PACKED")
        self.assertEqual(len(order.history), 2)
        order.set_address("1 Main St")
        self.assertEqual(order.apply("ship"), "SHIPPED")

    def test_refund_window_boundary_is_inclusive(self):
        order, clock = delivered_order()
        clock.now = 5000 + REFUND_WINDOW_SECONDS
        self.assertTrue(order.can("refund"))
        self.assertEqual(order.apply("refund"), "REFUNDED")

    def test_refund_after_window_fails(self):
        order, clock = delivered_order()
        clock.now = 5000 + REFUND_WINDOW_SECONDS + 1
        self.assertFalse(order.can("refund"))
        with self.assertRaises(GuardFailed):
            order.apply("refund")
        self.assertEqual(order.state, "DELIVERED")
        self.assertEqual(len(order.history), 4)

    def test_guard_failure_is_not_an_illegal_transition(self):
        self.assertFalse(issubclass(GuardFailed, IllegalTransition))
        self.assertTrue(issubclass(GuardFailed, OrderError))
        self.assertTrue(issubclass(IllegalTransition, OrderError))


class IllegalMoves(unittest.TestCase):
    def test_skipping_a_step(self):
        order, _ = make()
        order.add_item("pen", 1)
        with self.assertRaises(IllegalTransition):
            order.apply("ship")
        with self.assertRaises(IllegalTransition):
            order.apply("deliver")
        self.assertEqual(order.state, "NEW")
        self.assertEqual(order.history, [])

    def test_refund_only_after_delivery(self):
        order, _ = make()
        order.add_item("pen", 1)
        order.apply("pay")
        with self.assertRaises(IllegalTransition):
            order.apply("refund")

    def test_final_states_accept_nothing(self):
        for events in (("cancel",),):
            order, _ = make()
            to_state(order, *events)
            for e in ("pay", "pack", "ship", "deliver", "cancel", "refund"):
                self.assertFalse(order.can(e))
                with self.assertRaises(IllegalTransition):
                    order.apply(e)
        order, clock = delivered_order()
        order.apply("refund")
        with self.assertRaises(IllegalTransition):
            order.apply("refund")

    def test_unknown_event(self):
        order, _ = make()
        with self.assertRaises(ValueError):
            order.apply("explode")
        self.assertFalse(order.can("explode"))

    def test_can_does_not_change_anything(self):
        order, _ = make()
        order.add_item("pen", 1)
        self.assertTrue(order.can("pay"))
        self.assertFalse(order.can("pack"))
        self.assertEqual(order.state, "NEW")
        self.assertEqual(order.history, [])


class Items(unittest.TestCase):
    def test_items_only_while_new(self):
        order, _ = make()
        order.add_item("pen", 1)
        order.apply("pay")
        with self.assertRaises(OrderError):
            order.add_item("ink", 2)
        self.assertEqual(order.total_cents, 1)

    def test_invalid_prices(self):
        order, _ = make()
        for bad in (0, -5, 1.5, "10"):
            with self.assertRaises(ValueError):
                order.add_item("pen", bad)

    def test_address_validation_and_final_state(self):
        order, _ = make()
        with self.assertRaises(ValueError):
            order.set_address("   ")
        order.apply("cancel")
        with self.assertRaises(OrderError):
            order.set_address("1 Main St")


if __name__ == "__main__":
    unittest.main()
