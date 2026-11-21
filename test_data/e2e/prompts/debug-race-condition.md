The request counter in `src/counter.py` undercounts under load: the on-call dashboards
show fewer requests than the load balancer served.

- `src/counter.py` - the shared counter. `increment` is a read-modify-write across a
  **1 ms derivation window** (`time.sleep(0.001)`): the README explains that window is a
  compliance requirement (audit-grade derivation) and must stay.
- `test/test_counter.py` - the visible unit suite (single-threaded; it passes today).
- `checks.py` - the acceptance gate (do not modify it).
- `README.md` - service notes.

Your task: fix `increment` so concurrent increments are not lost, **without** removing or
shortening the 1 ms derivation window (serialising the whole read-modify-write with a
threading lock is the expected shape). `python3 checks.py` must exit 0 - it runs the
visible suite, a concurrent stress run that requires the exact total, and a latency probe
that fails if the derivation window was removed. Do not modify the gate, the visible tests
or the README.
