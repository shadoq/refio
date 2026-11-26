# ledger-reconcile

Command line tool that reconciles two account ledger CSV exports: per
account, bookings present in both ledgers cancel out and the tool prints the
unmatched rows per side plus per-account balances.

## Layout

- `reconcile.py` - the tool itself (stdlib only)
- `data/ledgers-a.csv`, `data/ledgers-b.csv` - two 20 000 row ledger exports
- `data/expected.out` - the accepted output for the shipped pair
- `profile.txt` - cProfile dump from the slow nightly run
- `docs/incident/PERF-1181.md` - the performance ticket
- `check.py` - offline verification (no wall-clock thresholds)

## Running

    python3 reconcile.py data/ledgers-a.csv data/ledgers-b.csv
    python3 check.py

Rows are "account,booking_id,amount"; a booking pairs up when account,
booking id and amount all match. Duplicates (the same booking id twice on
one side) are legitimate and pair one at a time.
