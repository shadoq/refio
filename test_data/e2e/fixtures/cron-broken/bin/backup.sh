#!/bin/sh
# backup.sh - nightly app data backup
tar -czf /var/backups/app-$(date +%F).tar.gz -C /var/lib/app data
