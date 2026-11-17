# Container findings - orders-service

## Summary

The container setup has a hardcoded production credential, runs everything as root, pins
nothing, and grants the cache sidecar privileged mode while publishing the service on every
interface. Any one of these would fail a production review; together they read as a dev setup
shipped to prod unchanged.

## Findings

### Finding 1 - Hardcoded production API key in the image

- Location: `Dockerfile`, `ENV API_KEY=sk-live-9f2c-a41d-prod`.
- Severity: Critical.
- CWE: CWE-798 (Use of Hard-coded Credentials).
- Why it matters: the key is baked into every image layer, so anyone with pull access to the
  registry (or a copy of the repo) holds a live production credential. `ENV` values are also
  visible in `docker inspect` and `docker history`.
- Remediation: remove the ENV line and inject the secret at runtime (env file / secret store
  mounted at start).

### Finding 2 - Container runs as root

- Location: `Dockerfile`, `USER root` (and no earlier non-root user is ever created).
- Severity: High.
- CWE: CWE-250 (Execution with Unnecessary Privileges).
- Why it matters: a compromise of the node process (e.g. via a dependency exploit) is
  immediately a root compromise of the container, which pairs badly with finding 4.
- Remediation: create an unprivileged app user and set `USER app` before the CMD.

### Finding 3 - Unpinned `latest` images

- Location: `Dockerfile` `FROM node:latest`; `docker-compose.yml` `image: redis:latest`.
- Severity: Medium.
- CWE: CWE-1104 (Use of Unmaintained Third Party Components) / supply-chain risk.
- Why it matters: builds are not reproducible and a compromised or breaking upstream tag
  flows into production unreviewed.
- Remediation: pin both images to digest or at least an exact version tag.

### Finding 4 - Privileged cache sidecar

- Location: `docker-compose.yml`, service `cache`, `privileged: true`.
- Severity: High.
- CWE: CWE-250 (container variant - unnecessary capabilities).
- Why it matters: `privileged: true` gives the sidecar effectively host-level access
  (all capabilities, device access); redis needs none of that. A compromise of the cache
  becomes a compromise of the host, not just the compose project.
- Remediation: drop `privileged: true`; add only the specific capabilities needed (none, for
  a plain redis).

### Finding 5 - Service published on all interfaces

- Location: `docker-compose.yml`, service `web`, `ports: "80:8080"`.
- Severity: Medium.
- CWE: CWE-668 (Exposure of Resource to Wrong Sphere).
- Why it matters: the short syntax binds 0.0.0.0 on the host, so the service is reachable
  from every network the host has - wider than the office firewall story in the README
  assumes if the host has more interfaces.
- Remediation: publish explicitly on the intended interface, e.g. `127.0.0.1:80:8080` (or
  the office VLAN address), behind the TLS terminator.
