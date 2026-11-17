#!/bin/sh
# cleanup.sh - hourly tidy-up of the scratch dir
find /var/tmp/app-scratch -type f -mtime +2 -delete 2>/dev/null || true
