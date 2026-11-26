#!/usr/bin/env python3
"""reconcile.py: reconcile two account ledgers (CSV) and print a diff.

Usage: python3 reconcile.py ledger-a.csv ledger-b.csv

Each ledger row is "account,booking_id,amount". The tool groups the rows by
account and, per account, pairs bookings from ledger A with bookings from
ledger B (matched bookings cancel out) and reports what is left: unmatched
rows, per ledger, plus a per-account balance summary. Output is plain text
and must stay byte-identical when the implementation changes.
"""
import csv
import re
import sys

BOOKING_RE = re.compile(r"^BK-[0-9]{6}$")
ACCOUNT_RE = re.compile(r"^ACC-[0-9]{4}$")


def load_ledger(path):
    """Read one ledger CSV into a list of (account, booking_id, amount)."""
    rows = []
    with open(path, newline="", encoding="utf-8") as fh:
        for rec in csv.reader(fh):
            if not rec or rec[0].startswith("#"):
                continue
            account, booking_id, amount = rec[0], rec[1], int(rec[2])
            rows.append((account, booking_id, amount))
    return rows


def group_by_account(rows):
    """Group rows by account id."""
    groups = {}
    for row in rows:
        account = row[0]
        if account not in groups:
            groups[account] = []
        groups[account].append(row)
    return groups


def reconcile(rows_a, rows_b):
    """Pair bookings between the two ledgers and return the report lines."""
    lines = []
    groups_a = group_by_account(rows_a)
    groups_b = group_by_account(rows_b)
    for account in sorted(set(groups_a) | set(groups_b)):
        lines.append("account %s" % account)
        remaining_a = list(groups_a.get(account, []))
        remaining_b = list(groups_b.get(account, []))
        for row_a in remaining_a:
            match = find_match(row_a, remaining_b)
            if match is not None:
                remaining_b.remove(match)
                remaining_a.remove(row_a)
        for row in sorted(remaining_a):
            lines.append("  A only %s %d" % (row[1], row[2]))
        for row in sorted(remaining_b):
            lines.append("  B only %s %d" % (row[1], row[2]))
        total_a = sum(r[2] for r in groups_a.get(account, []))
        total_b = sum(r[2] for r in groups_b.get(account, []))
        lines.append("  balance A %d" % total_a)
        lines.append("  balance B %d" % total_b)
    lines.append("accounts %d" % len(set(groups_a) | set(groups_b)))
    return lines


def find_match(row_a, rows_b):
    """Find the booking in rows_b that pairs with row_a."""
    for row_b in rows_b:
        if row_b[1] == row_a[1] and row_b[2] == row_a[2] and row_a in rows_b:
            return row_b
    return None


def main(argv):
    if len(argv) != 3:
        print("usage: reconcile.py <ledger-a.csv> <ledger-b.csv>", file=sys.stderr)
        return 2
    rows_a = load_ledger(argv[1])
    rows_b = load_ledger(argv[2])
    for line in reconcile(rows_a, rows_b):
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
