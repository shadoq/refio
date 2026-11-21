# Host triage - app01

## Key facts

hog_pid = 1003
zombie_pid = 1017
zombie_parent = 1005
worker_count = 3
worker_parent = 990

## Narrative

The snapshot shows a CPU hog, a zombie that 1005 never reaped, and the worker pool shape.
The kernel buffer excerpt was not consulted because the process table already tells the
whole story.
