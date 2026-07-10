Bug report: `parse_duration("1h30m")` returns 60. Expected: 90 (1 hour + 30 minutes).
The function lives in `src/dates.py`.

Do two things:
1. Add a regression test in a NEW file `test_regression.py` (project root, unittest style
   like `test_dates.py`) that covers the reported `1h30m` case and would fail without the
   fix.
2. Fix the root cause in `src/dates.py`.

Do not edit `test_dates.py`. When done,
`python3 -m unittest discover -s . -p "test_*.py"` must pass.
