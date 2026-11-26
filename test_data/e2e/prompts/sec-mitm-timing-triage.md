You are doing an offline TLS interception triage of a handshake timing
export (a stand-in project directory with `logs/tls-handshakes.csv`).

One peer group shows the classic interception pattern: systematically
doubled handshake durations and sessions that never resume, while the
other peers behave normally. Compute, per group (the pattern peer versus
everyone else):

Write `report/timing.txt` with exactly these three `key = value` lines
(no quotes, no markdown, no extra keys, exactly one line per key):

- `median_attack_ms = <n>` - the median handshake duration of the
  attack-pattern peer group
- `median_normal_ms = <n>` - the median handshake duration of all the
  other peers
- `verdict = <one short fixed phrase>` - the value must be exactly the
  phrase `interception suspected` when the attack median is more than
  1.5 times the normal median and the resumed=no share of the attack
  group is above 80 percent; otherwise `no anomaly`

Use the median (statistics.median or an equivalent), not the mean.
Verify with `python3 check.py` and iterate until it passes. `check.py`,
`README.md` and `logs/tls-handshakes.csv` must stay byte-identical; only
`report/timing.txt` may be created.
