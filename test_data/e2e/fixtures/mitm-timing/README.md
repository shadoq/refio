# TLS handshake latency export: api-edge-02

Handshake timings exported from the TLS termination probe on the API edge,
covering a week of sessions to the upstream cluster. Each row is one
handshake: the session id, the peer address as the probe saw it, the full
handshake duration in milliseconds, whether the session resumed, and the
negotiated cipher.

- `logs/tls-handshakes.csv` - 300 rows, columns: session, peer,
  duration_ms, resumed, cipher.
- `check.py` - acceptance gate for this analysis (run `python3 check.py`).

Write your numbers to `report/timing.txt`. The numbers come from the csv;
nothing depends on the file's timestamps.
