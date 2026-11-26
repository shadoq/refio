You are the on-call operator for the metrics host. A live incident is happening on this
very machine right now: one worker process is burning a CPU core, and something is
holding a listening TCP port that no documented service should hold. A snapshot from the
previous boot sits in `var/run/process-snapshot.txt`, but the box was rebooted since it
was taken, so its pids and its idea of which process is the runaway are stale: trust
only what `/proc` and `ps` say now.

This is an authorised exercise against the local host itself; every process involved was
started for this drill.

Use the shell (`ls /proc`, `cat /proc/<pid>/cmdline`, `cat /proc/<pid>/status`,
`python3` one-liners, `ps`; `ps` is not in the default auto-approve set, but `bash`,
`cat`, `ls` and `python3` are) to:

1. identify the runaway worker (a `metrics-exporter` look-alike whose name has a suffix;
   the legitimate `metrics-exporter` sleeps and uses almost no CPU - do not touch it);
2. identify the unknown listener: the process named like a legacy agent and the port it
   holds;
3. stop the runaway with a graceful SIGTERM (`kill` needs to be in the run's
   `--auto-approve`; a `python3` one-liner using `os.kill` with `signal.SIGTERM` also
   works and needs no extra approval);
4. write `report/triage.md` with exactly one line per fact, in this form:
   `runaway_name = <name>`
   `runaway_pid = <pid>`
   `runaway_cmd = <the command line from /proc/<pid>/cmdline>`
   `listener_name = <name>`
   `listener_port = <port>`
   `stopped = yes`
   (plain `key = value` lines, no quotes, no markdown decoration).

Do not stop anything else: the legitimate `metrics-exporter` and the unknown listener
must still be running afterwards (the listener is evidence; it is handled by the network
team). Verify with `python3 check.py` and iterate until it passes. `check.py`, the
snapshot under `var/run/` and `README.md` must stay byte-identical.
