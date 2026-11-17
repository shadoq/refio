The nightly digest job for the order dashboard crashed in production last night; the incident
report is in `crash-report.txt`. The same report shows a second, older symptom: events recorded
for one tenant started appearing in other tenants' digests.

Your task:

1. Reproduce and diagnose BOTH symptoms in `src/events.py` - the unhandled exception from the
   traceback and the cross-call state sharing shown in the log excerpt.
2. Fix `src/events.py` in place: keep the module's public behaviour for valid inputs (the
   visible tests in `test/` must stay green) and make both reported symptoms impossible.
3. `checks.py` replays the two failure modes plus the visible suite - run it with
   `python3 checks.py` and iterate until it passes.

Do not modify `crash-report.txt`, `checks.py`, the tests in `test/`, or the README: the fix
belongs in `src/events.py`.
