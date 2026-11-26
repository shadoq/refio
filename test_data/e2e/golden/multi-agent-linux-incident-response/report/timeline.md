# Intrusion timeline (triager)

## Timeline

- 2026-03-04T02:14:09Z: first failed password attempts for invalid user admin from 203.0.113.47
- 2026-03-04T02:14:41Z: successful password login for deploy from 203.0.113.47
- 2026-03-04T02:15:57Z: attacker creates backdoor account svc-backup (uid 0)
- 2026-03-04T02:16:40Z: ssh key login for svc-backup from 203.0.113.47
- 2026-03-04T02:17:12Z: first cron run of the implant /opt/monitord/monitord

first_access_time = 2026-03-04T02:14:09Z
attacker_ip = 203.0.113.47
persistence_count = 4

The persistence mechanisms are: the rogue cron job in etc/cron.d/monitord, the
backdoor account svc-backup with an authorized key, the implant in opt/monitord
and the root spool crontab entry.
