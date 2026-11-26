action = 1 | quarantined the implant to quarantine/monitord and recorded its sha256 in quarantine/manifest.txt
action = 2 | deleted the root spool crontab entry referencing /opt/monitord/monitord
action = 3 | removed the rogue cron line from etc/cron.d/monitord, keeping the backup-check job
action = 4 | locked the svc-backup account and removed the authorized_keys file
action = 5 | restored etc/ssh/sshd_config to the policy baseline
