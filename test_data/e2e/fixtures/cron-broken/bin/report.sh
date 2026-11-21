#!/bin/sh
# report.sh - weekly usage report
/usr/local/bin/app-stats --format weekly | mail -s "weekly report" ops@example.internal
