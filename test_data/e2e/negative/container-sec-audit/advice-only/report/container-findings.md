# Container findings - orders-service

## Summary

General container-hardening guidance for the team, collected during review of the Dockerfile
and docker-compose.yml.

## Recommendations

- Prefer minimal base images (alpine or distroless variants) to reduce attack surface.
- Keep images updated: rebuild regularly so the base stays current.
- Run an image scanner (trivy/grype) in CI and gate deploys on it.
- Enable read-only root filesystems and drop all Linux capabilities where possible.
- Use resource limits (cpus, memory) so a runaway container cannot starve the host.
- Set `restart: unless-stopped` for predictable behaviour.
- Sign images and verify signatures at deploy time.
- Audit ENV values in images for anything sensitive.

None of these are tied to a specific weakness observed in the files; they are standing
guidance for all of the team's container projects.
