Reduce the raw service log (`logs/service.log`, 4k+ lines) into the per-minute ERROR
summary exactly as `docs/format.md` specifies: a JSON object mapping `"YYYY-MM-DDTHH:MM"`
to the number of ERROR lines in that minute, only minutes that actually contain errors,
keys in ascending order. Write it to `report/errors.json`. `python3 checks.py` must exit
0 - it recomputes every count from the log and compares exactly (missing keys, wrong
counts and zero-count minutes all fail). Do not modify the log or the gate.
