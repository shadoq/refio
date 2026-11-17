# orders-service

A small Node HTTP service. The repo carries its own container setup:

- `Dockerfile` - builds the production image (the service listens on 8080).
- `docker-compose.yml` - runs the service plus a redis cache sidecar.
- `.env.example` - environment contract for local runs.

The compose file publishes the service on port 80 of the host, fronted by the office
firewall, and the redis sidecar is trusted infrastructure for the same compose project.
