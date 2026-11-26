You are doing an offline phish triage of a small mail spool snapshot (a
stand-in project directory with five raw `.eml` files under `mail/`).
The company domain is `corp.example.com`.

Write `report/senders.txt` with, in this order:

1. One verdict line per mail, one line for each of the five files, in the
   form `file: verdict reason` (single spaces, no markdown), where
   `verdict` is `legit` or `suspicious` and `reason` is exactly one
   keyword from this list:

   - `clean` - internal mail, headers and body consistent
   - `spoofed` - the displayed sender does not match the envelope sender
   - `pixel` - the body carries a tracking pixel (look at the HTML body,
     not just the headers or the subject)
   - `forward-mismatch` - inside a forwarded chain, the innermost
     Return-Path belongs to a different domain than the From it was
     forwarded for
   - `lookalike` - the sender domain is a lookalike of the company domain

2. Then exactly these two `key = value` lines (no quotes, no extra keys):

   - `pixel_url = <the exact URL of the tracking pixel, as it appears in
     the mail body>`
   - `forward_mismatch = <the address pair of the mismatched innermost
     step, as "inner From vs inner Return-Path">`

Verify with `python3 check.py` and iterate until it passes. `check.py`,
`README.md` and everything under `mail/` must stay byte-identical; only
`report/senders.txt` may be created.
