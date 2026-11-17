You are the on-call responder for an incident on host `web01`. You have two log files and
nothing else (the host is isolated; live response is out of scope for this task):

- `logs/auth.log` - sshd / sudo / useradd events for the window in question.
- `logs/web-access.log` - the front-end web access log for the same window.

Build the incident timeline and write it to `report/timeline.md`. The report must contain,
each on its own line in exactly this `key=value` form (single spaces around `=`):

- `attacker_ip=` the source address of the successful brute-force intrusion (the IP whose
  failed attempts are followed by a successful login - not a scanner that never got in),
- `compromised_account=` the account that was brute-forced successfully,
- `first_success=` the timestamp (HH:MM:SS) of the first successful login from the attacker,
- `escalation_command=` the command the intruder ran as root (from the sudo log),
- `persistence_user=` the account the intruder created for persistence.

Around those lines, add a short narrative timeline (what happened in which order, including
the corroborating web-log activity) - but the five `key=value` lines must appear verbatim.
`python3 checks.py` must exit 0 (it re-derives the five facts from the logs and matches them
against your report). Do not modify the logs, the gate or the README.
