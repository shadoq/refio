"""Stock ledger for a small parts warehouse.

Quantities are whole units, money is integer cents.
"""

from collections import namedtuple

Movement = namedtuple("Movement", "day sku category qty unit_cost kind")

KINDS = ("receipt", "issue", "adjust")


class StockLedger:
    """Journal of stock movements with reporting helpers."""

    def __init__(self, opening=None):
        self._entries = []
        self._opening = dict(opening or {})
        self._sku_category = {}

    def record(self, day, sku, category, qty, unit_cost=0, kind="receipt"):
        if kind not in KINDS:
            raise ValueError("unknown kind: %s" % kind)
        if kind in ("receipt", "issue") and qty <= 0:
            raise ValueError("qty must be positive for %s" % kind)
        self._sku_category[sku] = category
        self._entries.append(Movement(day, sku, category, qty, unit_cost, kind))

    def entries(self):
        return list(self._entries)

    def stock_on_hand(self, sku, day=None):
        qty = self._opening.get(sku, 0)
        for entry in self._entries:
            if entry.sku != sku:
                continue
            if day is not None and entry.day > day:
                continue
            qty += self._delta(entry)
        return qty

    @staticmethod
    def _delta(entry):
        if entry.kind == "issue":
            return -entry.qty
        return entry.qty

    @staticmethod
    def _title(first_day, last_day):
        return "STOCK STATEMENT days %d-%d" % (first_day, last_day)

    @staticmethod
    def _fmt_money(cents):
        sign = "-" if cents < 0 else ""
        whole, frac = divmod(abs(cents), 100)
        return "%s%d.%02d" % (sign, whole, frac)

    def build_statement(self, first_day, last_day, low_stock_threshold=5):
        """Render a plain-text stock statement for first_day..last_day inclusive."""
        if first_day > last_day:
            raise ValueError("first_day must not be after last_day")
        if low_stock_threshold < 0:
            raise ValueError("low_stock_threshold must not be negative")

        # split the journal into "before the window" and "inside the window"
        before = []
        window = []
        for entry in self._entries:
            if entry.day < first_day:
                before.append(entry)
            elif entry.day <= last_day:
                window.append(entry)

        # per-sku quantities
        rows = {}
        known = set(self._opening) | {e.sku for e in before} | {e.sku for e in window}
        for sku in known:
            rows[sku] = {
                "category": self._sku_category.get(sku, "uncategorised"),
                "opening": self._opening.get(sku, 0),
                "receipts": 0,
                "issues": 0,
                "adjust": 0,
                "receipt_qty": 0,
                "receipt_cents": 0,
            }
        for entry in before:
            rows[entry.sku]["opening"] += self._delta(entry)
        for entry in window:
            row = rows[entry.sku]
            if entry.kind == "receipt":
                row["receipts"] += entry.qty
                row["receipt_qty"] += entry.qty
                row["receipt_cents"] += entry.qty * entry.unit_cost
            elif entry.kind == "issue":
                row["issues"] += entry.qty
            else:
                row["adjust"] += entry.qty
        for row in rows.values():
            row["closing"] = row["opening"] + row["receipts"] - row["issues"] + row["adjust"]

        # valuation at the weighted average receipt cost, rounded half up;
        # skus without receipts in the window are valued at zero
        for row in rows.values():
            if row["receipt_qty"] > 0:
                row["avg_cost"] = (2 * row["receipt_cents"] + row["receipt_qty"]) // (2 * row["receipt_qty"])
            else:
                row["avg_cost"] = 0
            row["value"] = max(row["closing"], 0) * row["avg_cost"]

        # category subtotals
        categories = {}
        for sku, row in rows.items():
            cat = categories.setdefault(row["category"], {"skus": 0, "closing": 0, "value": 0})
            cat["skus"] += 1
            cat["closing"] += row["closing"]
            cat["value"] += row["value"]

        # flags: negative stock, low stock, and large manual adjustments
        flags = []
        for sku in sorted(rows):
            row = rows[sku]
            if row["closing"] < 0:
                flags.append("NEGATIVE %s (%d)" % (sku, row["closing"]))
            elif row["closing"] < low_stock_threshold:
                flags.append("LOW %s (%d)" % (sku, row["closing"]))
            if row["opening"] > 0 and abs(row["adjust"]) * 4 > row["opening"]:
                flags.append("ADJUSTED %s (%+d)" % (sku, row["adjust"]))

        # render the text
        lines = []
        lines.append(self._title(first_day, last_day))
        lines.append("%-8s %-10s %5s %5s %5s %5s %6s %10s" % (
            "SKU", "CATEGORY", "OPEN", "IN", "OUT", "ADJ", "CLOSE", "VALUE"))
        for sku in sorted(rows, key=lambda s: (rows[s]["category"], s)):
            row = rows[sku]
            lines.append("%-8s %-10s %5d %5d %5d %5d %6d %10s" % (
                sku, row["category"], row["opening"], row["receipts"], row["issues"],
                row["adjust"], row["closing"], self._fmt_money(row["value"])))
        lines.append("")
        lines.append("CATEGORIES")
        for name in sorted(categories):
            cat = categories[name]
            lines.append("  %-10s skus=%d closing=%d value=%s" % (
                name, cat["skus"], cat["closing"], self._fmt_money(cat["value"])))
        lines.append("")
        if flags:
            lines.append("FLAGS")
            for flag in flags:
                lines.append("  " + flag)
        else:
            lines.append("FLAGS none")
        total = sum(cat["value"] for cat in categories.values())
        lines.append("TOTAL VALUE %s" % self._fmt_money(total))
        return "\n".join(lines)

    def total_issued_bolts(self, first_day=None, last_day=None):
        """Units of bolts issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "bolts" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_bolts(self):
        """Average receipt unit cost of bolts in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "bolts" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_bolts(self):
        """Day with the most bolts movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "bolts":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_bolts(self, threshold):
        """Sorted bolts skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "bolts"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_gaskets(self, first_day=None, last_day=None):
        """Units of gaskets issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "gaskets" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_gaskets(self):
        """Average receipt unit cost of gaskets in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "gaskets" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_gaskets(self):
        """Day with the most gaskets movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "gaskets":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_gaskets(self, threshold):
        """Sorted gaskets skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "gaskets"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_filters(self, first_day=None, last_day=None):
        """Units of filters issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "filters" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_filters(self):
        """Average receipt unit cost of filters in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "filters" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_filters(self):
        """Day with the most filters movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "filters":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_filters(self, threshold):
        """Sorted filters skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "filters"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_cables(self, first_day=None, last_day=None):
        """Units of cables issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "cables" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_cables(self):
        """Average receipt unit cost of cables in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "cables" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_cables(self):
        """Day with the most cables movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "cables":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_cables(self, threshold):
        """Sorted cables skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "cables"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_valves(self, first_day=None, last_day=None):
        """Units of valves issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "valves" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_valves(self):
        """Average receipt unit cost of valves in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "valves" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_valves(self):
        """Day with the most valves movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "valves":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_valves(self, threshold):
        """Sorted valves skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "valves"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_seals(self, first_day=None, last_day=None):
        """Units of seals issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "seals" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_seals(self):
        """Average receipt unit cost of seals in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "seals" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_seals(self):
        """Day with the most seals movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "seals":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_seals(self, threshold):
        """Sorted seals skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "seals"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_belts(self, first_day=None, last_day=None):
        """Units of belts issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "belts" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_belts(self):
        """Average receipt unit cost of belts in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "belts" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_belts(self):
        """Day with the most belts movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "belts":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_belts(self, threshold):
        """Sorted belts skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "belts"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_fuses(self, first_day=None, last_day=None):
        """Units of fuses issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "fuses" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_fuses(self):
        """Average receipt unit cost of fuses in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "fuses" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_fuses(self):
        """Day with the most fuses movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "fuses":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_fuses(self, threshold):
        """Sorted fuses skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "fuses"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_hinges(self, first_day=None, last_day=None):
        """Units of hinges issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "hinges" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_hinges(self):
        """Average receipt unit cost of hinges in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "hinges" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_hinges(self):
        """Day with the most hinges movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "hinges":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_hinges(self, threshold):
        """Sorted hinges skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "hinges"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_bearings(self, first_day=None, last_day=None):
        """Units of bearings issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "bearings" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_bearings(self):
        """Average receipt unit cost of bearings in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "bearings" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_bearings(self):
        """Day with the most bearings movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "bearings":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_bearings(self, threshold):
        """Sorted bearings skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "bearings"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)
