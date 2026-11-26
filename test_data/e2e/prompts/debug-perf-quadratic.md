The nightly reconciliation job behind ticket PERF-1181 (see
`docs/incident/PERF-1181.md`) regressed from 5 seconds to over 3 minutes on
the real 50 000 row ledgers; `profile.txt` holds the cProfile dump from that
run and `data/` carries smaller 20 000 row copies that show the same shape.

Your task:

1. Read the profile and find the hot spots in `reconcile.py`.
2. Fix them without changing behaviour: the output on the shipped inputs must
   stay byte-identical to `data/expected.out`, and duplicate bookings must
   still pair one at a time.
3. Prove it: run `python3 check.py` and iterate until every check prints
   PASS. The gate checks output equality against its own reference
   implementation on a hidden input and a deterministic complexity bound, not
   wall-clock times.

Do not modify `check.py`, `data/`, `profile.txt`, `docs/` or `README.md`:
the fix belongs in `reconcile.py`. Your final message must include a line
`HOTSPOT: <what was slow and why>` and a line `FIX: <what you changed>`.
