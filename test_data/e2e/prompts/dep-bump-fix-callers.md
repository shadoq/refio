The vendored library `lib/formatting.py` was upgraded to v2: `format_price` now takes the
currency as a required keyword-only argument (it used to be positional). `python3 app.py`
now crashes with a TypeError.

Update the caller(s) in `app.py` to the new v2 signature. Do NOT edit
`lib/formatting.py` (it is the vendored dependency) and do not weaken the assertions.
When done, `python3 app.py` must print `OK`.
