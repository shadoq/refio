# Permission drift - app host

A rushed hardening pass left `etc/perms.csv` (the manifest the config-management pipeline
deploys) out of sync with the authoritative policy in `docs/perms-policy.md`. The tree in
this folder mirrors the paths the policy covers; the pipeline applies modes/owners from
the manifest, so the manifest is what must be fixed - do not chmod anything yourself.

Your task: repair `etc/perms.csv` so it implements the policy exactly (same header, one
row per policy path, no extras, no world-writable bit anywhere). `python3 checks.py` must
exit 0. Do not modify the policy, the gate or the mirrored tree.
