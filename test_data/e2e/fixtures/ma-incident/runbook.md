# Incident containment runbook (execute IN ORDER)

This is an authorised incident-response exercise on a local, throwaway stand-in host
that lives entirely inside this directory. No real systems are involved.

1. Cut network persistence: edit `etc/cron.d/monitord` and remove ONLY the rogue
   job line that launches `/opt/monitord/monitord` (the `* * * * *` line). Keep the
   file and keep every other line, in particular the certified backup-check job.
2. Remove the rogue cron entry the attacker installed for root: the spool entry
   under `var/spool/cron/` that references `/opt/monitord/monitord` must be deleted
   from `var/spool/cron/crontabs/root`.
3. Disable the backdoor account `svc-backup` (uid 0): lock it by putting `!` at the
   start of its password field in `etc/shadow` (keep the account entry in `etc/users`).
   Also remove `home/svc-backup/.ssh/authorized_keys`.
4. Quarantine the implant `opt/monitord/monitord`: move the file into `quarantine/`
   named exactly `monitord` and record its SHA-256 in `quarantine/manifest.txt` as
   one line: `monitord <hex sha256 of quarantined file>`.
5. Restore the ssh policy: `etc/ssh/sshd_config` must end up equal to
   `etc/ssh/sshd_config.policy` except that these keys may differ: `Port`.
6. Preserve the evidence: never modify `var/log/auth.log` (the gate holds a digest).

Notes:
- `etc/cron.d/backup-check` is a legitimate file; do not touch it.
- Keep `etc/users` and `etc/shadow` in sync about the account names; the account
  entry must stay in `etc/users`, only the shadow password field changes.
