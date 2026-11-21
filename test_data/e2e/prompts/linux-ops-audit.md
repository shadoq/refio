You are the on-call Linux administrator for this little server layout (a stand-in project
directory: `etc/`, `var/`, `shared/`, `bin/`).

Three things need to happen:

1. **Intrusion triage.** Something has been guessing SSH passwords; the evidence is in
   `var/log/auth.log`. Count every failed-password login attempt and identify the single IP
   address with the most failed attempts. Write `report/intrusion-report.txt` containing (at
   least) these two lines, exactly in this form:
   `failed_logins=<count>`
   `top_offender=<ip>`
2. **Permissions.** `shared/team-notes.txt` is currently world-writable; tighten it to owner
   read/write and group read (mode 640).
3. **Executability.** `bin/run-backup.sh` is invoked directly by cron but lost its executable
   bit; restore it.

`verify.sh` checks all three items - run it with `bash verify.sh` and iterate until it passes.

Do not modify `var/log/auth.log`, `verify.sh`, or anything under `etc/`, and do not change the
contents of `shared/team-notes.txt` (only its mode).
