The user `spike` (a member of the `contractors` group, see
`etc/group`) must be blocked from logging in to this host over SSH at all.
Edit `etc/ssh/sshd_config` so that `DenyUsers spike` takes effect globally,
for every connection. Mind sshd semantics: a directive placed inside a
`Match` block only applies to connections that satisfy that block's
criteria, and the file ends with a `Match Group contractors` block.

Change nothing else:

- keep the `Match Group contractors` block and the `PasswordAuthentication
  yes` grant inside it (the helpdesk pipeline depends on it),
- root, dana, lee and ruz must keep exactly the effective settings they
  have now, and must not be denied,
- do not touch `etc/passwd`, `etc/group`, `README.md` or `checks.py`.

`python3 checks.py` must exit 0. It parses the config the way sshd does
(first obtained value wins, Match lines apply only when their criteria hold
for the user, DenyUsers accumulates), derives group membership from
`etc/passwd` and `etc/group`, and verifies that spike is denied while root,
dana, lee and ruz keep byte-identical effective settings (SHA-256 compared)
and the contractors grant is still there. No sshd daemon is run.
