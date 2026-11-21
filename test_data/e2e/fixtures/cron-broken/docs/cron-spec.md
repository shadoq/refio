# Cron schedule policy (authoritative)

Every job in `etc/crontab` must match this table exactly (5 POSIX cron fields: minute,
hour, day-of-month, month, day-of-week; day-of-week is 0-7 where both 0 and 7 mean
Sunday - names like `sun` are NOT accepted on this host's cron):

| command                   | schedule       | meaning                                     |
|---------------------------|----------------|---------------------------------------------|
| /usr/local/bin/backup.sh  | `30 2 * * *`   | daily at 02:30                              |
| /usr/local/bin/cleanup.sh | `15 * * * *`   | hourly at :15                               |
| /usr/local/bin/report.sh  | `0 3 * * 0`    | Sundays at 03:00                            |
| /usr/local/bin/health.sh  | `*/5 9-17 * * 1-5` | every 5 minutes, 09:00-17:59, Monday-Friday |

Additional policy: every script under `bin/` that cron invokes must start with a shebang
line (`#!/...`) - without it the system cron silently fails to execute the file.
