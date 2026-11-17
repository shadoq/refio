The pricing test suite is flaky: CI fails maybe one run in three, always in
`test/test_pricing.py`, and never in the same place twice.

- `src/pricing.py` - the production module (do **not** change it: the flakes are in the
  tests, not in the code).
- `test/test_pricing.py` - the suite. Two of its tests are unstable:
  one asserts an **exact** outcome of a random sample that is never seeded, and one reads
  a scratch file that only exists because a sibling test happens to run first.
- `checks.py` - the acceptance gate (do not modify it): it runs the suite in four
  deterministic configurations (normal order, reversed order, and two shuffled orders,
  each under a fixed random seed and a clean scratch directory) and additionally rejects
  skipped or deleted tests.
- `README.md` - team notes.

Your task: make the suite deterministic **by fixing the tests** - seed or restructure the
random-sample assertion so it does not depend on unseeded randomness, and remove the
order dependency so every test arranges its own preconditions. `python3 checks.py` must
exit 0. You may not skip, delete or weaken a test into a no-op (the gate checks that all
original test methods still run), and `src/pricing.py` stays byte-identical.
