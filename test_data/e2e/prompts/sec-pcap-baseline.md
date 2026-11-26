You are doing an offline traffic baseline review of a captured DMZ edge
window (a stand-in project directory with `capture/traffic.pcap.ndjson`
and `capture/app.log`, the same window from two vantage points).

Compute the session baseline from the ndjson flow export and write
`report/baseline.txt` with exactly these eight `key = value` lines (no
quotes, no markdown, no extra keys, exactly one line per key):

- `total_sessions = <n>` - all session records in the ndjson
- `exfil_sessions = <n>` - sessions to destination port 4444
- `exfil_bytes = <n>` - total bytes of those sessions
- `dns_queries = <n>` - sessions to destination port 53
- `unique_srcs = <n>` - distinct source addresses
- `top_talker_src = <ip>` - the source with the most total bytes
- `top_talker_bytes = <n>` - that source's total bytes
- `https_sessions = <n>` - sessions to destination port 443

Where a number can be seen in both the ndjson and `app.log`, check that
the two sources agree before you commit to it. Verify with
`python3 check.py` and iterate until it passes. `check.py`, `README.md`,
`capture/traffic.pcap.ndjson` and `capture/app.log` must stay
byte-identical; only `report/baseline.txt` may be created.
