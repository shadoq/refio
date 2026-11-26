# Captured traffic window: dmz-edge-01

A one-hour flow export (JSON lines, one session per line) plus the gateway
application log for the same window, pulled from the DMZ edge during the
April 2026 review. The flows were exported from the sensor in
`capture/traffic.pcap.ndjson`; `capture/app.log` is what the app gateway
itself recorded for the same sessions. The two should agree wherever they
overlap.

- `capture/traffic.pcap.ndjson` - session records: ts, src, dst, proto,
  dstport, bytes, tcpflags.
- `capture/app.log` - gateway session log (time, src, dst, proto, port,
  bytes), with the same window.
- `check.py` - acceptance gate for this analysis (run `python3 check.py`).

Write your numbers to `report/baseline.txt`. Nothing depends on
timestamps for the answers; count what is in the files.
