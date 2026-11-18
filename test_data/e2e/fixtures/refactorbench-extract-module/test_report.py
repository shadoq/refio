import unittest
from datetime import date

from report import (
    business_days_between,
    days_between,
    format_invoice_line,
    is_weekend,
    late_fee,
    overdue_invoices,
    parse_iso_date,
    summary,
)

INVOICES = [
    {"id": "INV-1", "due": "2025-03-03", "amount_cents": 12050},
    {"id": "INV-2", "due": "2025-03-14", "amount_cents": 999, "paid": True},
    {"id": "INV-3", "due": "2025-03-07", "amount_cents": 500},
]


class DateHelpersStillWorkThroughReport(unittest.TestCase):
    def test_parse_iso_date(self):
        self.assertEqual(parse_iso_date(" 2025-03-03 "), date(2025, 3, 3))

    def test_days_between_is_signed(self):
        self.assertEqual(days_between("2025-03-03", "2025-03-10"), 7)
        self.assertEqual(days_between("2025-03-10", "2025-03-03"), -7)

    def test_is_weekend(self):
        self.assertTrue(is_weekend(date(2025, 3, 8)))
        self.assertFalse(is_weekend(date(2025, 3, 7)))

    def test_business_days_exclude_weekends_and_the_end_day(self):
        self.assertEqual(business_days_between("2025-03-03", "2025-03-10"), 5)
        self.assertEqual(business_days_between("2025-03-07", "2025-03-10"), 1)
        self.assertEqual(business_days_between("2025-03-10", "2025-03-03"), 0)


class InvoiceReport(unittest.TestCase):
    def test_line_format(self):
        self.assertEqual(format_invoice_line(INVOICES[0]), "INV-1    2025-03-03    120.50")

    def test_overdue_ignores_paid_and_future(self):
        self.assertEqual([i["id"] for i in overdue_invoices(INVOICES, "2025-03-12")], ["INV-1", "INV-3"])

    def test_late_fee_has_a_grace_period_in_business_days(self):
        self.assertEqual(late_fee(INVOICES[0], "2025-03-05"), 0)
        self.assertEqual(late_fee(INVOICES[0], "2025-03-12"), (7 - 2) * 150)
        self.assertEqual(late_fee(INVOICES[1], "2025-04-01"), 0)

    def test_summary(self):
        self.assertEqual(summary(INVOICES, "2025-03-12"), {"overdue": ["INV-1", "INV-3"], "fees_cents": 750 + 150})


if __name__ == "__main__":
    unittest.main()
