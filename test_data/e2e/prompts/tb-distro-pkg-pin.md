Decide for every line of `packages.txt` whether that version is
installable under the four pin stanzas of `etc/apt/preferences.d/pinning`
(rules spelled out in `docs/policy.md`) and write `report/allowed.txt` with
exactly one `name=version origin=<origin>` line per installable version
(sorted by name, then version, with the header line `docs/policy.md`
specifies) plus `report/denied-count.txt` with a single
`denied_count = N` line. Comparisons are exact and the version pin is a
glob. `python3 checks.py` must exit 0 - it re-applies the pins to the
snapshot and compares exactly. Do not modify the snapshot, the pinning file,
the docs or the gate.
