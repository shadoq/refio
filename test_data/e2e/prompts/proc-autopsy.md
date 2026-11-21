A colleague paged you about the app host being sluggish, and left you an evidence folder
(read `README.md` first): a `ps` snapshot (`ps-snapshot.txt`) and a kernel ring buffer
excerpt (`dmesg-oom.txt`) from the same boot. The live console is gone - the report is all
that will survive the hand-off.

Your task: analyse the evidence and write `report/proc-report.md`. The report must contain,
each on its own line, exactly this `key = value` form (single spaces around `=`):

- `hog_pid = <pid>` - the user-space process burning the most CPU right now (ignore kernel
  threads),
- `zombie_pid = <pid>` - the defunct process in the snapshot,
- `zombie_parent = <pid>` - the process that must reap it (its parent in the snapshot),
- `oom_victim = <pid>` - the process the kernel OOM-killer killed earlier in the boot
  (from dmesg; it must NOT appear in the ps snapshot),
- `worker_count = <n>` - how many `worker.py` processes are running,
- `worker_parent = <pid>` - the PID those workers share as their parent.

Around those lines add a short narrative (what you think happened on the host, and what the
on-call should do first). The five... the six `key = value` lines must appear verbatim -
`python3 checks.py` must exit 0 (it re-derives every fact from the evidence and matches
your report). Do not modify the evidence or the gate.
