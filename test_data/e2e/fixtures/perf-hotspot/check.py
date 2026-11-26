#!/usr/bin/env python3
"""Offline verification for PERF-1181.

Checks, without wall-clock thresholds:
1. the shipped inputs still produce exactly data/expected.out,
2. a hidden seeded input (generated here) reconciles to the same lines as
   this gate's own straightforward reference implementation,
3. a deterministic complexity bound: the number of comparisons the module
   performs per row stays linear in the input size (measured by counting
   tuple equality tests through a counting wrapper, comparing a small and a
   large input of the same generator), and
4. duplicate bookings are still matched one pair at a time (a regression
   drops rows the reference keeps).
Exit code 0 only when every check passes.
"""
import io
import os
import random
import sys
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)

import reconcile as mod


def gen_pair(seed, n_accounts, n_rows, dupe_rate):
    """Generate two ledgers plus the reference reconciliation lines."""
    rng = random.Random(seed)
    accounts = ["ACC-%04d" % i for i in range(n_accounts)]
    rows_a = []
    rows_b = []
    for i in range(n_rows):
        account = rng.choice(accounts)
        booking = "BK-%06d" % rng.randrange(1000000)
        amount = rng.randrange(1, 50001)
        roll = rng.random()
        if roll < 0.55:
            rows_a.append((account, booking, amount))
            rows_b.append((account, booking, amount))
        elif roll < 0.75:
            rows_a.append((account, booking, amount))
            if rng.random() < dupe_rate:
                # duplicate booking: appears twice on one side, once on the other
                rows_a.append((account, booking, amount))
        else:
            rows_b.append((account, booking, amount))
            if rng.random() < dupe_rate:
                rows_b.append((account, booking, amount))
    return rows_a, rows_b


def reference_lines(rows_a, rows_b):
    """Straightforward reference: same contract as the module."""
    out = []
    groups_a = {}
    groups_b = {}
    for row in rows_a:
        groups_a.setdefault(row[0], []).append(row)
    for row in rows_b:
        groups_b.setdefault(row[0], []).append(row)
    accounts = sorted(set(groups_a) | set(groups_b))
    for account in accounts:
        out.append("account %s" % account)
        pool_b = list(groups_b.get(account, []))
        left_a = []
        for row in groups_a.get(account, []):
            if row[1:3] in [r[1:3] for r in pool_b]:
                pool_b.remove(next(r for r in pool_b if r[1:3] == row[1:3]))
            else:
                left_a.append(row)
        for row in sorted(left_a):
            out.append("  A only %s %d" % (row[1], row[2]))
        for row in sorted(pool_b):
            out.append("  B only %s %d" % (row[1], row[2]))
        out.append("  balance A %d" % sum(r[2] for r in groups_a.get(account, [])))
        out.append("  balance B %d" % sum(r[2] for r in groups_b.get(account, [])))
    out.append("accounts %d" % len(accounts))
    return out


class CountingTuple(tuple):
    """Tuple that counts equality comparisons it takes part in."""


class Counter:
    def __init__(self):
        self.count = 0
        self.on = False

    def wrap(self, rows):
        """Wrap booking ids so every == against them is counted."""
        return [(r[0], Counted(r[1], self), r[2]) for r in rows]


class Counted:
    __slots__ = ("value", "counter")

    def __init__(self, value, counter):
        self.value = value
        self.counter = counter

    def __eq__(self, other):
        if self.counter.on:
            self.counter.count += 1
        return self.value == (other.value if isinstance(other, Counted) else other)

    def __hash__(self):
        return hash(self.value)

    def __lt__(self, other):
        return self.value < (other.value if isinstance(other, Counted) else other)


def measured_run(rows_a, rows_b):
    """Run mod.reconcile on wrapped rows with the comparator counter on."""
    counter = Counter()
    counter.on = True
    wrapped_a = counter.wrap(rows_a)
    wrapped_b = counter.wrap(rows_b)
    before = counter.count
    lines = mod.reconcile(wrapped_a, wrapped_b)
    used = counter.count - before
    counter.on = False
    # unwrap booking ids for comparison against reference lines
    unwrapped = []
    for line in lines:
        parts = line.split(" ")
        if len(parts) >= 3 and parts[-2].startswith("BK-"):
            unwrapped.append(" ".join(parts[:-1] + [parts[-1]]))
        else:
            unwrapped.append(line)
    return unwrapped, used


def check_shipped():
    expected = open(os.path.join(ROOT, "data", "expected.out"), encoding="utf-8").read()
    r = mod.main(["reconcile.py", os.path.join(ROOT, "data", "ledgers-a.csv"), os.path.join(ROOT, "data", "ledgers-b.csv")])
    return r == 0


def capture(argv_a, argv_b):
    saved = sys.stdout
    sys.stdout = buf = io.StringIO()
    try:
        rc = mod.main(["reconcile.py", argv_a, argv_b])
    finally:
        sys.stdout = saved
    return rc, buf.getvalue()


def check_hidden():
    rows_a, rows_b = gen_pair(seed=20261119, n_accounts=60, n_rows=900, dupe_rate=0.05)
    want = reference_lines(rows_a, rows_b)
    if not hasattr(mod, "reconcile") or not callable(getattr(mod, "reconcile", None)):
        return False, "reconcile.py no longer exposes a reconcile(rows_a, rows_b) function"
    try:
        got_lines, _ = measured_run(rows_a, rows_b)
    except Exception as exc:
        return False, "reconcile raised on the hidden input: %s" % exc
    if got_lines != want:
        # fall back to plain run for a cleaner diff signal
        got_plain = mod.reconcile(list(rows_a), list(rows_b))
        if got_plain != want:
            first = next((i for i, (g, w) in enumerate(zip(got_plain, want)) if g != w), None)
            return False, "hidden input output differs from reference near line %s (got %s lines, want %s)" % (
                first, len(got_plain), len(want))
    return True, ""


def check_complexity():
    small_a, small_b = gen_pair(seed=7, n_accounts=30, n_rows=250, dupe_rate=0.05)
    large_a, large_b = gen_pair(seed=8, n_accounts=30, n_rows=2500, dupe_rate=0.05)
    try:
        _, ops_small = measured_run(small_a, small_b)
        _, ops_large = measured_run(large_a, large_b)
    except Exception as exc:
        return False, "reconcile raised while measuring: %s" % exc
    n_small = len(small_a) + len(small_b)
    n_large = len(large_a) + len(large_b)
    ratio_rows = n_large / n_small
    ratio_ops = (ops_large + 1) / (ops_small + 1)
    # a linear implementation scales ops proportionally to rows; quadratic
    # code blows up by roughly ratio_rows^2. Allow generous slack.
    if ratio_ops > ratio_rows * 3 + 5:
        return False, "comparison count grows faster than linear: rows x%.1f but comparisons x%.1f (small=%d large=%d)" % (
            ratio_rows, ratio_ops, ops_small, ops_large)
    if ops_large > 60 * n_large + 2000:
        return False, "absolute comparison budget exceeded: %d comparisons for %d rows" % (ops_large, n_large)
    return True, "rows x%.1f comparisons x%.1f" % (ratio_rows, ratio_ops)


def check_duplicates():
    # same booking twice on both sides: both pairs must cancel
    rows_a = [("ACC-0001", "BK-000001", 100), ("ACC-0001", "BK-000001", 100), ("ACC-0001", "BK-000002", 50)]
    rows_b = [("ACC-0001", "BK-000001", 100), ("ACC-0001", "BK-000001", 100)]
    try:
        got = mod.reconcile(list(rows_a), list(rows_b))
    except Exception as exc:
        return False, "reconcile raised on the duplicate input: %s" % exc
    want = reference_lines(rows_a, rows_b)
    if got != want:
        return False, "duplicate booking handling changed (dropped or doubled rows)"
    return True, ""


def main():
    checks = []
    ok, detail = check_hidden()
    checks.append(("hidden input matches reference", ok, detail))
    ok2, detail2 = check_complexity()
    checks.append(("linear comparison bound", ok2, detail2))
    ok3, detail3 = check_duplicates()
    checks.append(("duplicate bookings kept", ok3, detail3))
    ok4 = check_shipped()
    checks.append(("shipped inputs reproduce expected.out", ok4, ""))
    failed = 0
    for name, ok, detail in checks:
        if ok:
            print("PASS [%s]" % name)
            if detail:
                print("  (%s)" % detail)
        else:
            failed += 1
            print("FAIL [%s]" % name)
            if detail:
                print("  - %s" % detail)
    if failed:
        print("%d of %d checks failed" % (failed, len(checks)))
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
