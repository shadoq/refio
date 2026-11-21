`parse_duration` in `timelog.py` mangles decimal hours: `"1.5h"` gives 300 minutes instead of 90, and `"0.25h"` gives 25 hours' worth instead of 15 minutes. Please fix it so decimal hours work, keeping the existing formats (`"1h30m"`, `"45m"`, `"90"`) as they are. The tests in `tests/test_timelog.py` describe the expected results.

Heads-up: `export_weekly_report` in the same file is my uncommitted work in progress. It is deliberately unfinished and has debug prints in it. Do not touch it, do not finish it and do not clean it up, I will commit it myself later.

Run the tests with `python -m unittest discover -s tests -t .`.
