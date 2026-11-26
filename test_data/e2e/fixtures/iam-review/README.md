# Refio access control (iam)

This repository is the access control plane of Refio's internal tools: the policy that is in
force, the engine that evaluates it, and the access logs of the last 30 days.

| Path | What it is |
| --- | --- |
| `policy/policy.json` | The policy document in force |
| `policy/groups.json` | Group membership |
| `src/iam/evaluator.py` | The decision engine (default deny, explicit Deny beats Allow) |
| `logs/access-2026-10-DD.jsonl` | One access log per day (UTC) |
| `docs/actions.md` | The actions each service exposes and their risk |
| `docs/policy-format.md` | Policy syntax and the house rules for changes |

Check a single request against the policy in force:

    python3 src/iam/evaluator.py dana files:Read files/runbooks/mysql.md

Every line of a log file is one request: `ts`, `principal`, `action`, `resource`, `result`
(`allow` or `deny`) and `source` (the network address the request came from).

## News

* 2026-10-20: engineering moved to the new office (subnet `10.8.9.0/24`).
* 2026-10-01: `svc-report` got a broad temporary statement (`report-export-temporary`) until
  the reporting job is moved to the new scheduler. It is still there.
* The two service accounts `svc-backup` and `svc-deploy` still carry the `*:*` statements
  from the original setup in 2024.
