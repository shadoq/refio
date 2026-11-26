The CI job for this library fails intermittently; incident FLAKY-77 in
`docs/incident/FLAKY-77.md` describes it and `ci/failures.txt` lists 20 real
runs. The workflow in `.github/workflows/ci.yml` does not pin the hash seed.

Your task:

1. Reproduce the order dependence locally (for example by running the suite
   under several different `PYTHONHASHSEED` values) and diagnose all root
   causes: the non-deterministic ordering with the documented tie-break in
   `docs/tie-break.md`, the dict mutation during iteration in the merge
   helper, and the non-injectable clock in the report.
2. Fix the root causes in `pyflaky/` so the behaviour is deterministic
   without pinning `PYTHONHASHSEED` anywhere in the repository.
3. Prove it: run `python3 check.py` and iterate until every check prints
   PASS. The tests in `tests/` must keep asserting the same behaviour.

Do not modify `check.py`, anything under `tests/`, `ci/`, `docs/`,
`.github/` or `README.md`: the fix belongs in `pyflaky/`. Your final message
must include a line `ROOT CAUSE: <one sentence per defect>` and a line
`FIX: <what you changed>`.
