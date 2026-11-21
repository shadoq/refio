`intervals.py` is a small library for half-open integer intervals (merge, covered length, membership, gaps, clipping, subtraction). It has no tests yet and we have been bitten by off-by-one bugs in code like this before.

Write unit tests in `tests/test_intervals.py` (`unittest`, `import intervals`). They will be judged by a mutation check: `python check_mutants.py` runs your tests against the real module (they must all pass, at least 6 tests) and against prepared faulty copies of the module in `mutants/`, and the tests must fail on at least 8 of the 10 faulty copies. So write tests that would notice small mistakes such as a wrong comparison operator, a missing sort or a lost boundary case, not only happy paths.

Do not change `intervals.py`, `check_mutants.py` or anything in `mutants/`, and do not make the tests read the module's source.
