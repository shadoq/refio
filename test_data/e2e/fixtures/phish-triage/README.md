# Mail spool snapshot: helpdesk tier 1

Five mails exported from the helpdesk queue during the April 2026 phish
exercise, plus one that was not part of the exercise. They are raw RFC822
`.eml` files as delivered; nothing was re-encoded on export.

- `mail/001-clean.eml` through `mail/005-lookalike.eml` - the export set.
- `check.py` - acceptance gate for this triage (run `python3 check.py`).

The company domain is `corp.example.com`. Write your triage to
`report/senders.txt`. Judge each mail on the full message: headers, body
and any embedded forwarded message. Nothing depends on timestamps.
