# Port wiring run

- `services/` - three stdlib services (ingress -> logic -> store); do not modify them.
- `docs/topology.md` - the authoritative wiring (ports, upstream URLs, data flow).
- `run.env` - the deployment wiring the services read (drifted).
- `checks.py` - the acceptance gate (do not modify it); it boots the chain end-to-end.

Repair `run.env` so the topology holds and the chain answers.
