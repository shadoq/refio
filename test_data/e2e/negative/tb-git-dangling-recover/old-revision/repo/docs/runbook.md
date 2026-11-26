# Runbook

revision: 6

Start the service with `make run`.
Stop it with `make stop`.
Backups run at 02:00.
Rotating logs live in var/log/.
Queue drain: bin/drain.sh.
Alerts forward to pager duty.
