# Container findings - orders-service

## Summary

The image bakes a live credential in and runs as root; images are unpinned and the service
is published on all interfaces.

## Findings

### Finding 1 - Hardcoded production API key

- Location: `Dockerfile`, `ENV API_KEY=...`.
- Severity: Critical.
- CWE: CWE-798.
- Remediation: inject at runtime from a secret store.

### Finding 2 - Container runs as root

- Location: `Dockerfile`, `USER root`.
- Severity: High.
- CWE: CWE-250.
- Remediation: `USER app` after creating an unprivileged user.

### Finding 3 - Unpinned images

- Location: `Dockerfile` `FROM node:latest`, compose `redis:latest`.
- Severity: Medium.
- CWE: CWE-1104.
- Remediation: pin to digest or exact tag.

### Finding 4 - Port published on all interfaces

- Location: `docker-compose.yml`, `ports: "80:8080"`.
- Severity: Medium.
- CWE: CWE-668.
- Remediation: bind the publish to the intended interface, e.g. `127.0.0.1:80:8080`.
