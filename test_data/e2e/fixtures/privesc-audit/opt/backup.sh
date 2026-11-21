#!/bin/bash
# /usr/local/bin/backup.sh (copy; live path is the same)
# Nightly backup of the app data directory. Runs as root from cron.
set -u

DEST="/var/backups/app"
SRC="/var/lib/app/data"

mkdir -p "$DEST"
tar -czf "$DEST/backup-$(date +%F).tar.gz" -C "$SRC" .
echo "backup done: $DEST/backup-$(date +%F).tar.gz"
