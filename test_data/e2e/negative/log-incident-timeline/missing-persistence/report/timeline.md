# Incident timeline - web01, Feb 11

## Key facts

attacker_ip = 198.51.100.23
compromised_account = svc_backup
first_success = 02:14:37
escalation_command = /bin/cat /etc/shadow

## Narrative

198.51.100.23 brute-forced svc_backup (five failures, then success at 02:14:37), read
/etc/shadow via sudo, and created a new user for persistence (see the 02:16 useradd entry in
the auth log).
