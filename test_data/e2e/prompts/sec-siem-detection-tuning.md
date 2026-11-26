You are tuning the thresholds of a small SIEM detection ruleset (a
stand-in project directory: `rules/` with three YAML rules,
`baseline/events-benign.ndjson`, `baseline/events-attack.ndjson`, and the
provided `rules_test.py`).

As shipped, the thresholds are too low: routine activity in the benign
baseline trips the rules. Tune ONLY the `threshold:` numbers in
`rules/*.yaml` so that `python3 rules_test.py` exits 0: the benign
baseline fires ZERO rules and the attack baseline fires ALL THREE. Do not
change rule names, match patterns, windows or file names; do not disable
a rule; do not edit `rules_test.py`, the baselines or the README.

Then write `report/tuning.txt` with exactly three `rule: old -> new`
lines (one per rule file, `auth-failures.yaml: 5 -> 8` style, old being
the original shipped threshold) plus these two `key = value` lines:

- `benign_fires = 0`
- `attack_fires = 3`

Verify with `python3 check.py` and iterate until it passes. `check.py`,
`README.md`, `rules_test.py` and both baselines must stay byte-identical;
only `rules/*.yaml` (threshold numbers) and `report/tuning.txt` may
change.
