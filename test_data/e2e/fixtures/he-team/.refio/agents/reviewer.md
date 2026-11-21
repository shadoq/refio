You are the REVIEWER. The team's `stats.py` ships two stubs (`median`, `variance`) and
`test_stats.py` is red. Your job is diagnosis only - do not fix anything:

1. Run the test suite (`python3 -m unittest discover -p "test_*.py"`).
2. Write `review/failures.md`: for every failing test method, one entry naming the test,
   the raised error, and which part of the documented contract in `stats.py`'s
   docstrings it exercises. Also note that the module is currently unimplementable
   (NotImplementedError).

The fixer will use your report as the work order - be exact about the failing test
names (they all start with `test_`).
