# Disk cleanup run - appdata volume

The appdata volume breached its soft quota again and the retention policy
(`docs/retention-policy.md`) has not been applied for weeks. The operator tooling only
executes what the manifest says, so this must be exact.

Your task: apply the policy **on paper** - derive the expired set from the filenames and
the reference date in the policy, and write it as `report/cleanup-plan.txt` (relative
paths, one per line, sorted ascending, nothing else). `python3 checks.py` must exit 0 (it
recomputes the expired set from the tree and compares your manifest, order and content).
Do not delete or modify any file, and do not modify the policy or the gate.
