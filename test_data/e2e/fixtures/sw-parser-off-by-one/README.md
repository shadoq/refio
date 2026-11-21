# csvlite - row reader for the billing export

`csvlite.py` is the minimal CSV reader used by the nightly billing export job:
one physical row at a time, comma-separated fields, optional double-quoted
fields and `""` escapes. It exports `split_row`, `parse` and `pad_row`.

`test/` holds the unit suite:

    python3 -m unittest discover -s test

The acceptance gate that replays the current corruption report:

    python3 checks.py
