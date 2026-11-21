# cleanup.sh - hourly tidy-up of the scratch dir (NOTE: no shebang - cron's ORPHAN)
find /var/tmp/app-scratch -type f -mtime +2 -delete 2>/dev/null || true
