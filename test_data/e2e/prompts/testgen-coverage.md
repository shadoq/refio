`timecard.py` calculates paid hours for our hourly staff, and it has no tests at all. Before we touch it, I want a safety net.

Write unit tests for it in `tests/test_timecard.py` (the `tests` package already exists) using `unittest`. Cover the normal cases and the error handling, with real assertions on the results, not just calls. `python check_coverage.py` measures the line coverage of the module with a tracer (the `coverage` package is not installed here) and must report `OK`: all tests pass, at least 8 tests, every test asserts something, and at least 90 percent of the lines of `timecard.py` are executed.

Do not change `timecard.py` or `check_coverage.py`. If a test fails because you believe the module is wrong, leave the module alone and say so in your final message.
