# Host triage - app01

## Key facts

hog_pid = 1015
zombie_pid = 1017
zombie_parent = 1005
oom_victim = 1013
worker_count = 3
worker_parent = 990

## Narrative

The most visible CPU in the snapshot belongs to the nginx worker processes, so one of them
must be the hog; the rest of the analysis stands - a zombie under 1005, an earlier OOM
kill, and a three-process worker pool under the supervisor.
