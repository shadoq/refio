import unittest

from shop.clients import all_clients, clear_clients, find_client, register_client
from shop.invoices import build_invoice, client_discount_percent
from shop.orders import clear_orders, orders_of_client, place_order
from shop.reports import client_summary, format_top_clients, top_clients


class ShopTest(unittest.TestCase):
    def setUp(self):
        clear_clients()
        clear_orders()
        register_client("c1", "Anna Nowak", "anna@example.test", "gold")
        register_client("c2", "Jan Kowalski", "jan@example.test")
        register_client("c3", "Ewa Lis", "ewa@example.test", "vip")
        place_order("o1", "c1", [("tea", 400, 3), ("cup", 1200, 1)])
        place_order("o2", "c2", [("tea", 400, 1)])
        place_order("o3", "c1", [("pot", 2500, 2)])
        place_order("o4", "c3", [("cup", 1200, 2)])

    def test_clients_and_orders(self):
        self.assertEqual([c.client_id for c in all_clients()], ["c1", "c2", "c3"])
        self.assertEqual(len(orders_of_client("c1")), 2)
        with self.assertRaises(KeyError):
            place_order("o9", "nobody", [])

    def test_discount_by_tier(self):
        self.assertEqual(client_discount_percent(find_client("c1")), 5)
        self.assertEqual(client_discount_percent(find_client("c2")), 0)
        self.assertEqual(client_discount_percent(find_client("c3")), 10)

    def test_invoice_text(self):
        order = orders_of_client("c1")[0]
        self.assertEqual(
            build_invoice(order),
            "INVOICE o1\n"
            "Client: Anna Nowak <anna@example.test>\n"
            "  tea x3 @ 400\n"
            "  cup x1 @ 1200\n"
            "Client discount: 5%\n"
            "DUE 2280",
        )

    def test_client_summary(self):
        self.assertEqual(client_summary("c1"), {"client_id": "c1", "orders": 2, "spend": 7400})
        self.assertEqual(client_summary("c2"), {"client_id": "c2", "orders": 1, "spend": 400})

    def test_top_clients_ranked_by_spend(self):
        self.assertEqual(top_clients(2), [("c1", 7400), ("c3", 2400)])

    def test_top_clients_text(self):
        self.assertEqual(
            format_top_clients(2),
            "TOP CLIENTS\n1. c1 (Anna Nowak) 7400\n2. c3 (Ewa Lis) 2400",
        )


if __name__ == "__main__":
    unittest.main()
