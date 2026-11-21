"""Stock ledger for a small parts warehouse.

Quantities are whole units, money is integer cents.
"""

from collections import namedtuple

Movement = namedtuple("Movement", "day sku category qty unit_cost kind")

KINDS = ("receipt", "issue", "adjust")


class WarehouseJournal:
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
    def _fmt_money(cents):
        sign = "-" if cents < 0 else ""
        whole, frac = divmod(abs(cents), 100)
        return "%s%d.%02d" % (sign, whole, frac)

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

    def reconcile_counts(self, counts, day, tolerance_pct=2):
        """Compare physical counts with the book stock on `day`.

        Returns (report_text, adjustments); adjustments is a sorted list of
        (sku, delta) pairs that would bring the book in line with the count.
        """
        if tolerance_pct < 0:
            raise ValueError("tolerance_pct must not be negative")
        for sku, qty in counts.items():
            if qty < 0:
                raise ValueError("count for %s is negative" % sku)

        book = self._book_quantities(day)
        last_cost = self._last_receipt_costs(day)
        results = self._classify_counts(book, counts, last_cost, tolerance_pct)
        self._assign_severity(results)
        adjustments = [(r["sku"], r["variance"]) for r in results if r["status"] in ("SHORT", "OVER")]
        return self._render_reconciliation(results, day, tolerance_pct), adjustments

    def _book_quantities(self, day):
        known = set(self._opening) | {e.sku for e in self._entries if e.day <= day}
        book = {}
        for sku in known:
            book[sku] = self.stock_on_hand(sku, day)
        return book

    def _last_receipt_costs(self, day):
        # the journal is in recording order so later receipts win
        last_cost = {}
        for entry in self._entries:
            if entry.day <= day and entry.kind == "receipt":
                last_cost[entry.sku] = entry.unit_cost
        return last_cost

    def _classify_counts(self, book, counts, last_cost, tolerance_pct):
        results = []
        for sku in sorted(set(book) | set(counts)):
            if sku not in book:
                results.append({"sku": sku, "status": "UNKNOWN", "book": 0,
                                "counted": counts[sku], "variance": counts[sku], "cents": 0})
                continue
            if sku not in counts:
                results.append({"sku": sku, "status": "UNCOUNTED", "book": book[sku],
                                "counted": None, "variance": 0, "cents": 0})
                continue
            variance = counts[sku] - book[sku]
            allowed = max(book[sku], 1) * tolerance_pct
            if abs(variance) * 100 <= allowed:
                status = "OK"
            elif variance < 0:
                status = "SHORT"
            else:
                status = "OVER"
            results.append({"sku": sku, "status": status, "book": book[sku],
                            "counted": counts[sku], "variance": variance,
                            "cents": variance * last_cost.get(sku, 0)})
        return results

    def _assign_severity(self, results):
        for result in results:
            cents = abs(result["cents"])
            if result["status"] not in ("SHORT", "OVER"):
                result["severity"] = "-"
            elif cents >= 10000:
                result["severity"] = "HIGH"
            elif cents >= 2000:
                result["severity"] = "MEDIUM"
            else:
                result["severity"] = "LOW"

    def _render_reconciliation(self, results, day, tolerance_pct):
        lines = ["COUNT RECONCILIATION day %d tolerance %d%%" % (day, tolerance_pct)]
        lines.append("%-8s %-9s %6s %7s %8s %-6s" % ("SKU", "STATUS", "BOOK", "COUNTED", "VARIANCE", "SEV"))
        for result in results:
            counted = "-" if result["counted"] is None else str(result["counted"])
            lines.append("%-8s %-9s %6d %7s %+8d %-6s" % (
                result["sku"], result["status"], result["book"], counted,
                result["variance"], result["severity"]))
        tally = {"OK": 0, "SHORT": 0, "OVER": 0, "UNKNOWN": 0, "UNCOUNTED": 0}
        for result in results:
            tally[result["status"]] += 1
        lines.append("SUMMARY ok=%d short=%d over=%d unknown=%d uncounted=%d" % (
            tally["OK"], tally["SHORT"], tally["OVER"], tally["UNKNOWN"], tally["UNCOUNTED"]))
        short_value = sum(-r["cents"] for r in results if r["status"] == "SHORT")
        over_value = sum(r["cents"] for r in results if r["status"] == "OVER")
        lines.append("SHORT VALUE %s OVER VALUE %s" % (self._fmt_money(short_value), self._fmt_money(over_value)))
        escalate = [r["sku"] for r in results if r["severity"] == "HIGH"]
        if escalate:
            lines.append("ESCALATE " + ", ".join(escalate))
        return "\n".join(lines)

    def total_issued_springs(self, first_day=None, last_day=None):
        """Units of springs issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "springs" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_springs(self):
        """Average receipt unit cost of springs in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "springs" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_springs(self):
        """Day with the most springs movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "springs":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_springs(self, threshold):
        """Sorted springs skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "springs"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_nozzles(self, first_day=None, last_day=None):
        """Units of nozzles issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "nozzles" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_nozzles(self):
        """Average receipt unit cost of nozzles in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "nozzles" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_nozzles(self):
        """Day with the most nozzles movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "nozzles":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_nozzles(self, threshold):
        """Sorted nozzles skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "nozzles"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_clamps(self, first_day=None, last_day=None):
        """Units of clamps issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "clamps" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_clamps(self):
        """Average receipt unit cost of clamps in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "clamps" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_clamps(self):
        """Day with the most clamps movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "clamps":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_clamps(self, threshold):
        """Sorted clamps skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "clamps"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_rivets(self, first_day=None, last_day=None):
        """Units of rivets issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "rivets" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_rivets(self):
        """Average receipt unit cost of rivets in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "rivets" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_rivets(self):
        """Day with the most rivets movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "rivets":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_rivets(self, threshold):
        """Sorted rivets skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "rivets"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_washers(self, first_day=None, last_day=None):
        """Units of washers issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "washers" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_washers(self):
        """Average receipt unit cost of washers in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "washers" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_washers(self):
        """Day with the most washers movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "washers":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_washers(self, threshold):
        """Sorted washers skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "washers"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_brackets(self, first_day=None, last_day=None):
        """Units of brackets issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "brackets" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_brackets(self):
        """Average receipt unit cost of brackets in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "brackets" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_brackets(self):
        """Day with the most brackets movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "brackets":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_brackets(self, threshold):
        """Sorted brackets skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "brackets"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_sensors(self, first_day=None, last_day=None):
        """Units of sensors issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "sensors" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_sensors(self):
        """Average receipt unit cost of sensors in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "sensors" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_sensors(self):
        """Day with the most sensors movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "sensors":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_sensors(self, threshold):
        """Sorted sensors skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "sensors"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_relays(self, first_day=None, last_day=None):
        """Units of relays issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "relays" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_relays(self):
        """Average receipt unit cost of relays in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "relays" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_relays(self):
        """Day with the most relays movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "relays":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_relays(self, threshold):
        """Sorted relays skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "relays"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_gears(self, first_day=None, last_day=None):
        """Units of gears issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "gears" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_gears(self):
        """Average receipt unit cost of gears in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "gears" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_gears(self):
        """Day with the most gears movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "gears":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_gears(self, threshold):
        """Sorted gears skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "gears"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_pulleys(self, first_day=None, last_day=None):
        """Units of pulleys issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "pulleys" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_pulleys(self):
        """Average receipt unit cost of pulleys in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "pulleys" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_pulleys(self):
        """Day with the most pulleys movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "pulleys":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_pulleys(self, threshold):
        """Sorted pulleys skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "pulleys"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def build_reorder_plan(self, as_of_day, window_days=14, lead_days=7, cover_days=21, pack_size=1):
        """Suggest purchase quantities from the issues of the trailing window.

        Returns the plan as text. Only skus that were issued during the window
        and whose stock is at or under the reorder point are listed.
        """
        for name, value in (("window_days", window_days), ("lead_days", lead_days),
                            ("cover_days", cover_days), ("pack_size", pack_size)):
            if value <= 0:
                raise ValueError("%s must be positive" % name)

        consumed = self._consumption(as_of_day, window_days)
        candidates = self._reorder_candidates(consumed, as_of_day, window_days, lead_days, cover_days, pack_size)
        candidates.sort(key=lambda c: (not c["urgent"], c["days_left"], c["sku"]))
        return self._render_reorder_plan(candidates, as_of_day, window_days, lead_days, cover_days)

    def _consumption(self, as_of_day, window_days):
        start = as_of_day - window_days + 1
        consumed = {}
        for entry in self._entries:
            if entry.kind == "issue" and start <= entry.day <= as_of_day:
                consumed[entry.sku] = consumed.get(entry.sku, 0) + entry.qty
        return consumed

    def _reorder_candidates(self, consumed, as_of_day, window_days, lead_days, cover_days, pack_size):
        candidates = []
        for sku in sorted(consumed):
            used = consumed[sku]
            on_hand = self.stock_on_hand(sku, as_of_day)
            reorder_point = -(-used * lead_days // window_days)
            target = -(-used * (lead_days + cover_days) // window_days)
            if on_hand > reorder_point:
                continue
            missing = max(target - on_hand, 1)
            order_qty = -(-missing // pack_size) * pack_size
            days_left = max(on_hand, 0) * window_days // used
            candidates.append({
                "sku": sku,
                "category": self._sku_category.get(sku, "uncategorised"),
                "on_hand": on_hand,
                "used": used,
                "order_qty": order_qty,
                "days_left": days_left,
                "urgent": days_left < lead_days,
            })
        return candidates

    def _group_by_category(self, candidates):
        groups = {}
        for item in candidates:
            groups.setdefault(item["category"], []).append(item)
        return groups

    def _render_reorder_plan(self, candidates, as_of_day, window_days, lead_days, cover_days):
        lines = ["REORDER PLAN as of day %d (window %d, lead %d, cover %d)" % (
            as_of_day, window_days, lead_days, cover_days)]
        if not candidates:
            lines.append("nothing to order")
            return "\n".join(lines)
        groups = self._group_by_category(candidates)
        for category in sorted(groups):
            lines.append(category.upper())
            for item in groups[category]:
                marker = "URGENT" if item["urgent"] else "plan"
                lines.append("  %-8s on_hand=%-4d used=%-4d order=%-4d days_left=%-3d %s" % (
                    item["sku"], item["on_hand"], item["used"], item["order_qty"],
                    item["days_left"], marker))
        urgent_count = sum(1 for c in candidates if c["urgent"])
        total_units = sum(c["order_qty"] for c in candidates)
        lines.append("TOTAL %d skus, %d urgent, %d units" % (len(candidates), urgent_count, total_units))
        return "\n".join(lines)

    def total_issued_couplings(self, first_day=None, last_day=None):
        """Units of couplings issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "couplings" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_couplings(self):
        """Average receipt unit cost of couplings in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "couplings" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_couplings(self):
        """Day with the most couplings movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "couplings":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_couplings(self, threshold):
        """Sorted couplings skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "couplings"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_fittings(self, first_day=None, last_day=None):
        """Units of fittings issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "fittings" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_fittings(self):
        """Average receipt unit cost of fittings in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "fittings" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_fittings(self):
        """Day with the most fittings movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "fittings":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_fittings(self, threshold):
        """Sorted fittings skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "fittings"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_switches(self, first_day=None, last_day=None):
        """Units of switches issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "switches" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_switches(self):
        """Average receipt unit cost of switches in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "switches" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_switches(self):
        """Day with the most switches movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "switches":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_switches(self, threshold):
        """Sorted switches skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "switches"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_lamps(self, first_day=None, last_day=None):
        """Units of lamps issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "lamps" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_lamps(self):
        """Average receipt unit cost of lamps in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "lamps" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_lamps(self):
        """Day with the most lamps movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "lamps":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_lamps(self, threshold):
        """Sorted lamps skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "lamps"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_pumps(self, first_day=None, last_day=None):
        """Units of pumps issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "pumps" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_pumps(self):
        """Average receipt unit cost of pumps in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "pumps" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_pumps(self):
        """Day with the most pumps movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "pumps":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_pumps(self, threshold):
        """Sorted pumps skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "pumps"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_hoses(self, first_day=None, last_day=None):
        """Units of hoses issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "hoses" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_hoses(self):
        """Average receipt unit cost of hoses in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "hoses" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_hoses(self):
        """Day with the most hoses movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "hoses":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_hoses(self, threshold):
        """Sorted hoses skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "hoses"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_plugs(self, first_day=None, last_day=None):
        """Units of plugs issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "plugs" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_plugs(self):
        """Average receipt unit cost of plugs in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "plugs" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_plugs(self):
        """Day with the most plugs movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "plugs":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_plugs(self, threshold):
        """Sorted plugs skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "plugs"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_caps(self, first_day=None, last_day=None):
        """Units of caps issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "caps" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_caps(self):
        """Average receipt unit cost of caps in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "caps" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_caps(self):
        """Day with the most caps movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "caps":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_caps(self, threshold):
        """Sorted caps skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "caps"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_anchors(self, first_day=None, last_day=None):
        """Units of anchors issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "anchors" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_anchors(self):
        """Average receipt unit cost of anchors in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "anchors" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_anchors(self):
        """Day with the most anchors movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "anchors":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_anchors(self, threshold):
        """Sorted anchors skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "anchors"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_latches(self, first_day=None, last_day=None):
        """Units of latches issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "latches" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_latches(self):
        """Average receipt unit cost of latches in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "latches" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_latches(self):
        """Day with the most latches movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "latches":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_latches(self, threshold):
        """Sorted latches skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "latches"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_trays(self, first_day=None, last_day=None):
        """Units of trays issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "trays" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_trays(self):
        """Average receipt unit cost of trays in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "trays" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_trays(self):
        """Day with the most trays movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "trays":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_trays(self, threshold):
        """Sorted trays skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "trays"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)

    def total_issued_lids(self, first_day=None, last_day=None):
        """Units of lids issued, optionally limited to a day range."""
        total = 0
        for entry in self._entries:
            if entry.category != "lids" or entry.kind != "issue":
                continue
            if first_day is not None and entry.day < first_day:
                continue
            if last_day is not None and entry.day > last_day:
                continue
            total += entry.qty
        return total

    def average_receipt_cost_lids(self):
        """Average receipt unit cost of lids in cents, rounded half up, or None."""
        qty = 0
        cents = 0
        for entry in self._entries:
            if entry.category == "lids" and entry.kind == "receipt":
                qty += entry.qty
                cents += entry.qty * entry.unit_cost
        if qty == 0:
            return None
        return (2 * cents + qty) // (2 * qty)

    def busiest_day_lids(self):
        """Day with the most lids movements; the earliest day wins a tie."""
        counts = {}
        for entry in self._entries:
            if entry.category == "lids":
                counts[entry.day] = counts.get(entry.day, 0) + 1
        if not counts:
            return None
        best = max(counts.values())
        return min(day for day, count in counts.items() if count == best)

    def skus_below_lids(self, threshold):
        """Sorted lids skus whose stock on hand is under threshold."""
        skus = {e.sku for e in self._entries if e.category == "lids"}
        return sorted(s for s in skus if self.stock_on_hand(s) < threshold)
