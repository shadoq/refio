During an internal assessment you are logged in as the low-privileged `auditor` account on
host `db01`. You cannot run anything interactive on the host itself; what you have is a set
of recon artifacts already collected in `recon/` (and one script copy in `opt/`):

- `recon/sudo-l.txt` - `sudo -l` output for the auditor account.
- `recon/suid.txt` - SUID binaries found on the host.
- `recon/crontab-root.txt` - root's crontab.
- `recon/writable.txt` - files writable by our group (`auditors`).
- `recon/notes.txt` - patch-level notes from the host's change log.
- `opt/backup.sh` - a copy of the backup script root's cron runs (its live path is
  `/usr/local/bin/backup.sh`).

Your task: work out **every viable path from this account to root**, and write them to
`report/privesc.md`. For each viable path give:

- the exact command (or short command sequence) that gets a root shell or executes a payload
  as root,
- the artifact it comes from,
- why it works.

Also explicitly cover the candidate you reject: which one is **not** viable and the specific
evidence (version, patch level) that rules it out. Do not modify any project file - the
deliverable is the report only.
