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
import sys

BOOKING_RE_HEADER = "BK-"
ACCOUNT_RE_HEADER = "ACC-"


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
        groups.setdefault(row[0], []).append(row)
    return groups


def reconcile(rows_a, rows_b):
    """Pair bookings between the two ledgers and return the report lines.

    Bookings pair up when account, booking id and amount all match. Duplicate
    bookings (same account, id and amount) are all matched, one at a time,
    because a booking that appears k times in both ledgers cancels k pairs;
    that behaviour is part of the byte-identical output contract.
    """
    lines = []
    groups_a = group_by_account(rows_a)
    groups_b = group_by_account(rows_b)
    all_accounts = set(groups_a) | set(groups_b)
    for account in sorted(all_accounts):
        lines.append("account %s" % account)
        # index the B side once per account: key -> list of rows
        index_b = {}
        for row in groups_b.get(account, []):
            index_b.setdefault((row[1], row[2]), []).append(row)
        counts_a = {}
        for row in groups_a.get(account, []):
            counts_a[(row[1], row[2])] = counts_a.get((row[1], row[2]), 0) + 1
        remaining_a = []
        for row in sorted(groups_a.get(account, [])):
            key = (row[1], row[2])
            if index_b.get(key):
                index_b[key].pop(0)
            else:
                remaining_a.append(row)
        remaining_b = []
        for key, rows in index_b.items():
            remaining_b.extend(rows)
        for row in remaining_a:
            lines.append("  A only %s %d" % (row[1], row[2]))
        for row in sorted(remaining_b):
            lines.append("  B only %s %d" % (row[1], row[2]))
        total_a = sum(r[2] for r in groups_a.get(account, []))
        total_b = sum(r[2] for r in groups_b.get(account, []))
        lines.append("  balance A %d" % total_a)
        lines.append("  balance B %d" % total_b)
    lines.append("accounts %d" % len(all_accounts))
    return lines


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
