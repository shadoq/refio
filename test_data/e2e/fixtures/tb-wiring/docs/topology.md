# Service topology (authoritative)

Three stdlib services form a chain; every service reads its own wiring from the
environment (run.env in this mirror is what the deployment applies):

| service | port env        | port | upstream env     | upstream value                |
|---------|-----------------|------|------------------|-------------------------------|
| ingress | INGRESS_PORT    | 8771 | INGRESS_UPSTREAM | http://127.0.0.1:8772         |
| logic   | LOGIC_PORT      | 8772 | LOGIC_UPSTREAM   | http://127.0.0.1:8773         |
| store   | STORE_PORT      | 8773 | - (chain end)    | -                             |

Data flow: `ingress /fetch?value=N` -> `logic /transform` (adds 1) -> `store /store`
(multiplies by 7, answers `stored: <n>`). The ingress must answer a valid fetch with
exactly the store's answer.

`run.env` drifted after the last change (an upstream points at a decommissioned port
and one variable is missing entirely). Repair run.env so the topology above holds
exactly - same variable names, same ports, nothing extra. `python3 checks.py` must exit
0: it validates the env file against the topology and then actually boots all three
services with it and probes `ingress /fetch?value=2` end-to-end. Do not modify the
services, the topology doc or the gate.
