# Incident timeline - web01, Feb 11

## Key facts

attacker_ip = 198.51.100.23
compromised_account = svc_backup
first_success = 02:14:37
escalation_command = /bin/cat /etc/shadow
persistence_user = backdoor

## Narrative

- 02:11:02-02:13:15 - five `Failed password` attempts for `svc_backup` from 198.51.100.23
  (auth.log). The other failed-login sources behave like scanners: 203.0.113.9 tried `admin`
  twice and never succeeded; 192.0.2.55 probed an invalid user `oracle` once.
- 02:13:58-02:14:10 - the same IP shows up in the web log: two failed `POST /login`
  attempts (401) followed by a `GET /admin` (403) - credential probing from the same source.
- 02:14:37 - `Accepted password for svc_backup` from 198.51.100.23: the brute force
  succeeded; session 47 opens at 02:14:44.
- 02:15:12 - the intruder runs `/bin/cat /etc/shadow` as root via sudo (privilege
  escalation / credential harvesting), and returns to `GET /admin` in the web log.
- 02:16:03 - `/usr/sbin/useradd -m backdoor` via sudo; useradd confirms the new account at
  02:16:40 (UID 1007, shell /bin/bash) - persistence.

## Recommended follow-up

Disable `svc_backup` and `backdoor`, rotate every credential in `/etc/shadow`'s reach, and
block 198.51.100.23 at the perimeter.
