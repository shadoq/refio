The local pipeline is red. Run `python ci.py`: it runs lint, the unit tests and a build check, and it stops at the first stage that fails. Get it fully green with `CI OK`.

Fix the problems in the `shipcalc` package. The pipeline script `ci.py` and the tests in `tests/` are the contract and must stay as they are, so do not loosen the lint rules, skip a stage or change test expectations.
