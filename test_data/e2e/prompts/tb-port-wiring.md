The three-service chain in `services/` (ingress -> logic -> store, one probe per hop,
data flow documented in `docs/topology.md`) is wired through `run.env`, which drifted
after the last change: one upstream points at a decommissioned port, one variable is
missing entirely, and the logic service sits on the wrong port.

Your task: repair `run.env` so it implements `docs/topology.md` exactly (same variable
names, same ports, nothing extra). `python3 checks.py` must exit 0 - it validates the
env against the topology, then actually boots all three services with your wiring and
probes `ingress /fetch?value=2` end-to-end (expecting `stored: 21`). Do not modify the
services, the topology doc or the gate.
