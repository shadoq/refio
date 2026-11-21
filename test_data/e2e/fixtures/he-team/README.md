# Team run - the stats module is red

The `stats.py` module ships two stubs and the test suite (`test_stats.py`) is failing on
both. The reviewer agent runs the tests first and documents exactly what fails and why;
the fixer agent then implements the module so the suite is green.

- `stats.py` - the stubs (fixer's work target).
- `test_stats.py` - the acceptance tests (do not modify them).
- `.refio/agents/` - the reviewer and fixer profiles.
