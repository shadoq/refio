# IR note - web01

The monitoring rule fired on repeated sshd failures for `svc_backup` on web01 in the early
hours of Feb 11. The host has been isolated. Collected so far:

- `logs/auth.log` - the sshd/sudo/useradd events for the window (plus the usual cron noise
  and unrelated failed logins - scanners hit this box regularly).
- `logs/web-access.log` - the front-end access log for the same window.

We need the first-response timeline: who got in, when, what they ran as root, and what they
left behind. The web log should corroborate the source.
