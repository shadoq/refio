# Release matrix (authoritative)

The link farm in `releases/` is deployed by the config-management pipeline from the single
source of truth below. After the last release train someone hand-edited the farm and it
has drifted.

| slot      | version     | note                                  |
|-----------|-------------|---------------------------------------|
| prod      | v2.6.1      | current production build              |
| canary    | v2.7.0-rc3  | the promoted release candidate        |
| rollback  | v2.6.0      | previous production build             |
| demo      | v2.7.0-rc3  | demo tracks the canary build          |
| perf      | v2.6.1      | performance rig pins the prod build   |
| current   | v2.6.1      | `current` always tracks prod          |

Every slot must point at a version that exists in `releases/versions/` (each version has a
build manifest there). The farm file `releases/links.txt` is the artifact the pipeline
deploys - fix it to match this matrix exactly; do not edit the matrix, the gate or the
version dirs.
