`summarize()` in `report.py` is killing the nightly box: it reads the whole request log into memory, and the logs now run to hundreds of thousands of lines (they will keep growing). Make it work with a small, roughly constant amount of memory regardless of file size, returning exactly the same result as today (including the count of unreadable lines).

`python checks.py` generates a large log, compares the result and measures peak memory. It must pass. Do not edit `checks.py`.
