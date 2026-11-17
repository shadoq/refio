# Host triage - app01

## Key facts

hog_pid = 1003
zombie_pid = 1017
zombie_parent = 1005
oom_victim = 1013
worker_count = 3
worker_parent = 990

## Narrative

- `stress-ng` (1003) is the CPU hog: it is not ours, sits outside the supervisor tree and
  has burned 822 minutes of CPU time - someone left a load test attached to PID 1. Kill it
  first.
- The workers (1006, 1008, 1011) all belong to the supervisor (990) and look healthy.
- 1017 `[renderer]` is defunct: its parent 1005 (`reap_loop.sh`) is not reaping, so the
  zombie will sit there until 1005 exits or calls wait(). Restarting the reap loop fixes
  it.
- The kernel OOM-killed 1013 (`renderer`) earlier in the boot (dmesg) - it is already gone
  and must not show up in the ps snapshot; that memory pressure is behind, not current.
