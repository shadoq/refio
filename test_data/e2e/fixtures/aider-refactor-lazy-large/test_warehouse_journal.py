import unittest

from warehouse_journal import WarehouseJournal


def build_journal():
    j = WarehouseJournal(opening={"B-100": 40, "G-200": 12, "F-300": 30, "C-400": 5, "P-500": 60})
    j.record(1, "B-100", "bolts", 10, 250, "receipt")
    j.record(2, "B-100", "bolts", 15, 0, "issue")
    j.record(3, "G-200", "gaskets", 20, 1100, "receipt")
    j.record(4, "G-200", "gaskets", 25, 0, "issue")
    j.record(5, "F-300", "filters", 3, 0, "issue")
    j.record(6, "C-400", "cables", 8, 450, "receipt")
    j.record(7, "C-400", "cables", 9, 0, "issue")
    j.record(8, "B-100", "bolts", 30, 280, "issue")
    j.record(9, "G-200", "gaskets", 4, 1200, "receipt")
    j.record(10, "P-500", "pumps", 4, 0, "issue")
    j.record(11, "B-100", "bolts", 2, 0, "adjust")
    j.record(12, "P-500", "pumps", 2, 9000, "receipt")
    j.record(13, "G-200", "gaskets", 6, 0, "issue")
    return j


COUNTS = {"B-100": 41, "G-200": 12, "F-300": 20, "C-400": 3, "X-777": 4, "P-500": 59}

RECON_ALL = """COUNT RECONCILIATION day 13 tolerance 2%
SKU      STATUS      BOOK COUNTED VARIANCE SEV   
B-100    OVER           7      41      +34 MEDIUM
C-400    SHORT          4       3       -1 LOW   
F-300    SHORT         27      20       -7 LOW   
G-200    OVER           5      12       +7 MEDIUM
P-500    OK            58      59       +1 -     
X-777    UNKNOWN        0       4       +4 -     
SUMMARY ok=1 short=2 over=2 unknown=1 uncounted=0
SHORT VALUE 4.50 OVER VALUE 169.00"""
RECON_STRICT = """COUNT RECONCILIATION day 8 tolerance 0%
SKU      STATUS      BOOK COUNTED VARIANCE SEV   
B-100    OVER           5     100      +95 HIGH  
C-400    UNCOUNTED      4       -       +0 -     
F-300    UNCOUNTED     27       -       +0 -     
G-200    UNCOUNTED      7       -       +0 -     
P-500    UNCOUNTED     60       -       +0 -     
SUMMARY ok=0 short=0 over=1 unknown=0 uncounted=4
SHORT VALUE 0.00 OVER VALUE 237.50
ESCALATE B-100"""
PLAN_DEFAULT = """REORDER PLAN as of day 13 (window 14, lead 7, cover 21)
BOLTS
  B-100    on_hand=7    used=45   order=83   days_left=2   URGENT
CABLES
  C-400    on_hand=4    used=9    order=14   days_left=6   URGENT
GASKETS
  G-200    on_hand=5    used=31   order=57   days_left=2   URGENT
TOTAL 3 skus, 3 urgent, 154 units"""
PLAN_TIGHT = """REORDER PLAN as of day 13 (window 14, lead 3, cover 10)
BOLTS
  B-100    on_hand=7    used=45   order=36   days_left=2   URGENT
GASKETS
  G-200    on_hand=5    used=31   order=24   days_left=2   URGENT
TOTAL 2 skus, 2 urgent, 60 units"""
PLAN_NOTHING = """REORDER PLAN as of day 0 (window 14, lead 7, cover 21)
nothing to order"""


class ReconcileTest(unittest.TestCase):
    def setUp(self):
        self.journal = build_journal()

    def test_report_and_adjustments_for_a_mixed_count(self):
        text, adjustments = self.journal.reconcile_counts(COUNTS, 13)
        self.assertEqual(text, RECON_ALL)
        self.assertEqual(adjustments, [('B-100', 34), ('C-400', -1), ('F-300', -7), ('G-200', 7)])

    def test_zero_tolerance_flags_every_difference(self):
        text, adjustments = self.journal.reconcile_counts({"B-100": 100}, 8, tolerance_pct=0)
        self.assertEqual(text, RECON_STRICT)
        self.assertEqual(adjustments, [('B-100', 95)])

    def test_negative_count_is_rejected(self):
        with self.assertRaises(ValueError):
            self.journal.reconcile_counts({"B-100": -1}, 13)

    def test_negative_tolerance_is_rejected(self):
        with self.assertRaises(ValueError):
            self.journal.reconcile_counts({}, 13, tolerance_pct=-1)


class ReorderPlanTest(unittest.TestCase):
    def setUp(self):
        self.journal = build_journal()

    def test_default_plan(self):
        self.assertEqual(self.journal.build_reorder_plan(13), PLAN_DEFAULT)

    def test_short_window_with_pack_rounding(self):
        plan = self.journal.build_reorder_plan(13, window_days=14, lead_days=3, cover_days=10, pack_size=6)
        self.assertEqual(plan, PLAN_TIGHT)

    def test_nothing_issued_nothing_to_order(self):
        self.assertEqual(self.journal.build_reorder_plan(0), PLAN_NOTHING)

    def test_non_positive_parameters_are_rejected(self):
        with self.assertRaises(ValueError):
            self.journal.build_reorder_plan(13, pack_size=0)
        with self.assertRaises(ValueError):
            self.journal.build_reorder_plan(13, window_days=0)


class HelperTest(unittest.TestCase):
    def test_a_few_per_category_reports(self):
        journal = build_journal()
        self.assertEqual(journal.total_issued_gaskets(), 31)
        self.assertEqual(journal.average_receipt_cost_pumps(), 9000)
        self.assertEqual(journal.busiest_day_bolts(), 1)
        self.assertEqual(journal.skus_below_cables(5), ["C-400"])
        self.assertIsNone(journal.busiest_day_hoses())


if __name__ == "__main__":
    unittest.main()
