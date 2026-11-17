#!/bin/sh
# health.sh - business-hours liveness probe
curl -fsS -m 5 http://127.0.0.1:8080/health >/dev/null || echo "app unhealthy" >>/var/log/app/health-alerts.log
