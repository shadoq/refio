The session tier has a memory incident: RSS grows about 40 MB a day per worker and the
restart-on-OOM bandaid is wearing thin.

- `src/sessions.py` - the session store.
- `report/heap-profile.txt` - the tracemalloc snapshot the on-call captured at t=24h
  (RSS 41 MB where ~8 MB was expected); read it before touching anything.
- `checks.py` - the acceptance gate (do not modify it).
- `README.md` - service notes.

Your task: fix the leak in `src/sessions.py` so that:

1. The **audit trail** stops retaining payload copies - compliance needs the *number* of
   sessions ever issued, not their bytes (the `issued` counter already provides it).
2. The **live session map** is bounded with an LRU policy capped at **2048** entries
   (evict the least recently used when the cap is exceeded). `get` must still return the
   data of live sessions, and recency must update on access.
3. The `issued` counter keeps counting **every** session ever opened (including evicted
   ones) - the compliance dashboards read it.

`python3 checks.py` must exit 0 - it runs a functional pass (open/get/close/issued), a
20k open/close churn whose traced peak must stay under threshold, and an unbounded-open
pass whose peak must stay under threshold. Do not modify the profile, the gate or the
README.
