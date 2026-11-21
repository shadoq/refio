import unittest

from stock_ledger import StockLedger


def build_ledger():
    led = StockLedger(opening={"B-100": 40, "G-200": 12, "F-300": 3, "C-400": 0})
    led.record(1, "B-100", "bolts", 10, 25, "receipt")
    led.record(2, "B-100", "bolts", 15, 30, "issue")
    led.record(3, "G-200", "gaskets", 20, 110, "receipt")
    led.record(3, "G-200", "gaskets", 5, 110, "issue")
    led.record(4, "F-300", "filters", 2, 0, "issue")
    led.record(5, "B-100", "bolts", 30, 28, "receipt")
    led.record(5, "B-100", "bolts", 12, 0, "adjust")
    led.record(6, "C-400", "cables", 8, 450, "receipt")
    led.record(7, "C-400", "cables", 9, 0, "issue")
    led.record(8, "F-300", "filters", -2, 0, "adjust")
    led.record(9, "G-200", "gaskets", 4, 120, "receipt")
    led.record(9, "N-900", "nozzles", 6, 75, "receipt")
    return led


STATEMENT_3_8 = """STOCK STATEMENT days 3-8
SKU      CATEGORY    OPEN    IN   OUT   ADJ  CLOSE      VALUE
B-100    bolts         35    30     0    12     77      21.56
C-400    cables         0     8     9     0     -1       0.00
F-300    filters        3     0     2    -2     -1       0.00
G-200    gaskets       12    20     5     0     27      29.70

CATEGORIES
  bolts      skus=1 closing=77 value=21.56
  cables     skus=1 closing=-1 value=0.00
  filters    skus=1 closing=-1 value=0.00
  gaskets    skus=1 closing=27 value=29.70

FLAGS
  ADJUSTED B-100 (+12)
  NEGATIVE C-400 (-1)
  NEGATIVE F-300 (-1)
  ADJUSTED F-300 (-2)
TOTAL VALUE 51.26"""

STATEMENT_1_9_LOW_10 = """STOCK STATEMENT days 1-9
SKU      CATEGORY    OPEN    IN   OUT   ADJ  CLOSE      VALUE
B-100    bolts         40    40    15    12     77      20.79
C-400    cables         0     8     9     0     -1       0.00
F-300    filters        3     0     2    -2     -1       0.00
G-200    gaskets       12    24     5     0     31      34.72
N-900    nozzles        0     6     0     0      6       4.50

CATEGORIES
  bolts      skus=1 closing=77 value=20.79
  cables     skus=1 closing=-1 value=0.00
  filters    skus=1 closing=-1 value=0.00
  gaskets    skus=1 closing=31 value=34.72
  nozzles    skus=1 closing=6 value=4.50

FLAGS
  ADJUSTED B-100 (+12)
  NEGATIVE C-400 (-1)
  NEGATIVE F-300 (-1)
  ADJUSTED F-300 (-2)
  LOW N-900 (6)
TOTAL VALUE 60.01"""

STATEMENT_EMPTY_WINDOW = """STOCK STATEMENT days 10-12
SKU      CATEGORY    OPEN    IN   OUT   ADJ  CLOSE      VALUE
B-100    bolts         77     0     0     0     77       0.00
C-400    cables        -1     0     0     0     -1       0.00
F-300    filters       -1     0     0     0     -1       0.00
G-200    gaskets       31     0     0     0     31       0.00
N-900    nozzles        6     0     0     0      6       0.00

CATEGORIES
  bolts      skus=1 closing=77 value=0.00
  cables     skus=1 closing=-1 value=0.00
  filters    skus=1 closing=-1 value=0.00
  gaskets    skus=1 closing=31 value=0.00
  nozzles    skus=1 closing=6 value=0.00

FLAGS
  NEGATIVE C-400 (-1)
  NEGATIVE F-300 (-1)
TOTAL VALUE 0.00"""


class StatementTest(unittest.TestCase):
    def setUp(self):
        self.ledger = build_ledger()

    def test_statement_for_a_mid_journal_window(self):
        self.assertEqual(self.ledger.build_statement(3, 8), STATEMENT_3_8)

    def test_statement_with_a_higher_low_stock_threshold(self):
        self.assertEqual(self.ledger.build_statement(1, 9, low_stock_threshold=10), STATEMENT_1_9_LOW_10)

    def test_window_after_the_last_movement_still_lists_every_sku(self):
        self.assertEqual(self.ledger.build_statement(10, 12), STATEMENT_EMPTY_WINDOW)

    def test_reversed_window_is_rejected(self):
        with self.assertRaises(ValueError):
            self.ledger.build_statement(5, 4)

    def test_negative_threshold_is_rejected(self):
        with self.assertRaises(ValueError):
            self.ledger.build_statement(1, 2, low_stock_threshold=-1)


class ReportingHelpersTest(unittest.TestCase):
    def setUp(self):
        self.ledger = build_ledger()

    def test_stock_on_hand_respects_the_day(self):
        self.assertEqual(self.ledger.stock_on_hand("B-100"), 40 + 10 - 15 + 30 + 12)
        self.assertEqual(self.ledger.stock_on_hand("B-100", day=2), 35)

    def test_total_issued_in_a_day_range(self):
        self.assertEqual(self.ledger.total_issued_bolts(), 15)
        self.assertEqual(self.ledger.total_issued_bolts(first_day=3), 0)

    def test_average_receipt_cost_rounds_half_up(self):
        self.assertEqual(self.ledger.average_receipt_cost_gaskets(), (2 * (20 * 110 + 4 * 120) + 24) // 48)
        self.assertIsNone(self.ledger.average_receipt_cost_fuses())

    def test_busiest_day_prefers_the_earliest_on_a_tie(self):
        self.assertEqual(self.ledger.busiest_day_gaskets(), 3)

    def test_skus_below_threshold(self):
        self.assertEqual(self.ledger.skus_below_filters(5), ["F-300"])

    def test_record_rejects_a_non_positive_receipt(self):
        with self.assertRaises(ValueError):
            self.ledger.record(1, "X-1", "bolts", 0, 10, "receipt")


if __name__ == "__main__":
    unittest.main()
