#!/usr/bin/env bash
# Acceptance gate for the Linux on-call task.
#
# Checks the intrusion report's two required lines, the tightened file mode and the restored
# executable bit. Exit 0 only when every check passes. This file is part of the scenario
# definition - editing it is not a fix.
#
# Run: bash verify.sh
set -u
fail=0

# --- intrusion report ----------------------------------------------------------
if [ ! -f report/intrusion-report.txt ]; then
    echo "FAIL - report/intrusion-report.txt is missing"
    fail=1
else
    grep -qx 'failed_logins=9' report/intrusion-report.txt \
        || { echo "FAIL - failed-password count wrong (want line: failed_logins=9)"; fail=1; }
    grep -qx 'top_offender=203\.0\.113\.77' report/intrusion-report.txt \
        || { echo "FAIL - top offending ip wrong (want line: top_offender=203.0.113.77)"; fail=1; }
fi

# --- file permissions ----------------------------------------------------------
mode="$(stat -c %a shared/team-notes.txt 2>/dev/null || stat -f %Lp shared/team-notes.txt 2>/dev/null || echo '?')"
[ "$mode" = "640" ] || { echo "FAIL - shared/team-notes.txt mode is $mode, want 640"; fail=1; }

[ -x bin/run-backup.sh ] || { echo "FAIL - bin/run-backup.sh is not executable"; fail=1; }

if [ "$fail" -eq 0 ]; then
    echo "verify: all checks passed"
fi
exit "$fail"
