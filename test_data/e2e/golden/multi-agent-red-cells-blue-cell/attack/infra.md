# infra cell findings

- GET /metrics is reachable without authentication and exposes internal telemetry.
- GET /debug/env returns the whole process configuration, including NOTES_SECRET
  (sensitive configuration exposure, CWE-200).

infra_finding = /metrics | exposed-telemetry
infra_finding = /debug/env | config-leak
flag = FLAG{cells_fanin_both_paths_71c4}
