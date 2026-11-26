# metrics host triage

A stand-in project directory for the metrics host `metrics-vm-03`. The interesting part
of a live incident is happening on the machine itself right now: processes are burning
CPU, something is listening where nothing should, and the only reliable source is
`/proc` and `ps` (a stale snapshot from the previous boot sits in `var/run/` and is
known to be out of date; the box was rebooted since it was taken).

Write your findings to `report/triage.md` in the exact line format the on-call runbook
requires, and verify with `python3 check.py`.
