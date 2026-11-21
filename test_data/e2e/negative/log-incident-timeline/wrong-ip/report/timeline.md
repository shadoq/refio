# Incident timeline - web01, Feb 11

## Key facts

attacker_ip = 203.0.113.9
compromised_account = svc_backup
first_success = 02:14:37
escalation_command = /bin/cat /etc/shadow
persistence_user = backdoor

## Narrative

The most active failed-login source in the window was 203.0.113.9 (repeated attempts
against the admin account), so the intrusion is attributed to that address; svc_backup was
compromised shortly after, /etc/shadow was read via sudo, and a backdoor account was
created.
