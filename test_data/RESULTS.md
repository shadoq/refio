# Refio e2e - results log (iteration overview)

A single-file overview of successive e2e agent runs over the scenarios in `e2e/`
(runner `benchmark/scripts/e2e-run.sh`). Newest iteration on top. Historical data
consolidated from the local development journals.

**How to add an iteration:** run the suite with `E2E_OUT_DIR=<dir>` (collects `results.jsonl`),
build the `scenario x model` matrix, and add a dated section at the top.

**Verdict:** `PASS` / `FAIL[failure_mode]`. **Failure modes:** `wrong-output` (edited the wrong way /
wrong place), `loop` (looping / repetition abort), `agent-fail` (turn abandoned / INCOMPLETE),
`build-fail` (`build_cmd` != 0 despite SUCCESS), `crash` (no run.json), `overflow` (context overflow),
`none` (PASS). N = runs per scenario (N=1 is a point sample, not a stabilized pass-rate).

---
