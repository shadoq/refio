# Session tier

`src/sessions.py` is the per-worker session store. Ops captured a tracemalloc profile at
t=24h (`report/heap-profile.txt`): RSS 41 MB against an ~8 MB expectation, dominated by
`SessionStore.open`'s audit-trail append. The compliance dashboards read only the `issued`
counter.
