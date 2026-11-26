# Detection rules workbench: soc-poc-2

The SOC proof-of-concept detection rules for the API tier, with the two
labeled baselines we regression-test them against. The rules were tuned
in a hurry before the last demo and we know the thresholds shipped too
low: routine ops activity trips them. They still need to catch the attack
replay, obviously.

- `rules/` - three threshold-style YAML rules (fire when the count of
  matching events in the window exceeds the threshold).
- `baseline/events-benign.ndjson` - a labeled week of normal traffic.
- `baseline/events-attack.ndjson` - the labeled attack replay.
- `rules_test.py` - the regression runner: benign must fire zero rules,
  attack must fire all of them. `python3 rules_test.py` exits 0 only
  then, and it enumerates `rules/` itself.
- `check.py` - acceptance gate for this tuning round (run `python3 check.py`).

Tune the thresholds, run the tests, write the changelog to
`report/tuning.txt`.
