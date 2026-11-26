# edgegate bastion sshd_config

- `etc/ssh/sshd_config` - the live config. The `Match Group contractors`
  block at the bottom is intentional (the helpdesk one-time-password
  pipeline depends on it) and must stay.
- `etc/passwd`, `etc/group` - the account and group database of this host
  (do not modify).
- `checks.py` - the acceptance gate (do not modify it).

The account `spike` (a contractor, see `etc/group`) must be locked out of
SSH entirely. Every other account must keep exactly the effective settings
it has today.
