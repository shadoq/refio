Derive the edge SLO error-budget numbers from `logs/access.log`
(nginx combined format, 900 lines) exactly as `docs/slo.md` specifies and write
them to `report/error-budget.txt` as `key = value` lines: total_requests,
http_5xx, five_x_ratio (exactly 3 decimals), http_499, unique_clients_5xx and
top_5xx_client. `python3 checks.py` must exit 0 - it recomputes every number
from the log and compares exactly, and it rejects malformed lines. Do not
modify the log or the gate.
