# Transfer ledger

`src/accounts.py` moves money between accounts; each account owns its own lock and a
transfer holds both for its duration (the 50 ms in-flight window models the settlement
call and must stay).

Known symptom: concurrent opposite-direction transfers intermittently hang the worker.
