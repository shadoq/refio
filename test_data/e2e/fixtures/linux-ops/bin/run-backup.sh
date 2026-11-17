#!/usr/bin/env bash
# Nightly backup wrapper. cron invokes this file directly, so it must be executable.
set -euo pipefail

DEST="${BACKUP_DIR:-./backups}"
mkdir -p "$DEST"
tar czf "$DEST/app-data.tgz" etc shared
echo "backup written to $DEST/app-data.tgz"
