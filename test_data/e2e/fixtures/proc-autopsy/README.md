# Ops hand-off - app01

A colleague paged you about the app host being sluggish. Before the terminal access
expired, someone collected two pieces of evidence into this folder:

- `ps-snapshot.txt` - a full `ps` snapshot of the moment (columns: PID PPID STAT %CPU %MEM
  TIME COMMAND; bracketed names are kernel threads).
- `dmesg-oom.txt` - the kernel ring buffer excerpt from the same boot, including the
  OOM-killer notice.

Everything you report must be re-derivable from these two files - the console is gone.
