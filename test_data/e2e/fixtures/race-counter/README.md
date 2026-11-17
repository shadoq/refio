# Request counter service

`src/counter.py` is the shared request counter the worker threads increment. The ~1 ms
window inside `increment` stands in for the audit-grade value derivation: compliance
requires the derivation to take at least 1 ms, so it must not be removed or shortened when
changing the code.

Known symptom: the counter undercounts under concurrent load while the single-threaded
tests stay green.
