# Containment actions (responder)

action = 1 | removed the rogue * * * * * /opt/monitord/monitord line from etc/cron.d/monitord, keeping the backup-check job
action = 2 | deleted the root spool crontab entry referencing /opt/monitord/monitord from var/spool/cron/crontabs/root
action = 3 | locked the svc-backup backdoor account (password field now starts with !) and removed home/svc-backup/.ssh/authorized_keys
action = 4 | quarantined the implant to quarantine/monitord and recorded its sha256 in quarantine/manifest.txt
action = 5 | restored etc/ssh/sshd_config to the policy baseline (PermitRootLogin no, PasswordAuthentication no)
