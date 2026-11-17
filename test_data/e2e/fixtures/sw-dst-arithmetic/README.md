# room booking scheduler

`scheduler.py` moves meetings by whole days on the local wall clock ("same
slot, N days later") in Europe/Warsaw, where the offset flips between CET and
CEST twice a year. Naive datetimes are read as local wall time;
`weekly_slots()` expands a meeting into its weekly occurrences and
`describe()` renders the local timestamp for the confirmation email.

Run the acceptance gate with:

    python3 checks.py
