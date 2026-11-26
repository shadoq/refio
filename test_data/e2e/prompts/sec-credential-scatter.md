You are sanitizing an offline workstation snapshot (a stand-in
project directory: `home/dev/`, a developer home captured during a credential
exposure drill). One revoked service account API key is still scattered
around this home. The key matches the pattern `ACCT-XXXXXXXXXXXXXXXX`
(16 uppercase alphanumeric characters after the dash). The one specific
value appears in these five places:

- `home/dev/.bash_history`
- `home/dev/.gitconfig`
- `home/dev/notes.txt`
- `home/dev/backup.tar` (a real tar archive: one member config file holds it)
- `home/dev/app/settings.ini`

Replace EVERY occurrence of that key with the literal string `REDACTED`.
Some files carry it more than once. The tar counts too: extract it, redact,
and repack or overwrite the member so the archived config no longer holds
the key either. Do not delete files or truncate them: each location must
keep its other content and carry the `REDACTED` marker where the key used
to be.

Then write `report/creds.txt` with exactly one line per location from the
list above, in this format (project-relative path, a colon, a space, the
number of occurrences you replaced there), plus one final line:

    home/dev/<file>: <occurrences>
    ...
    key_id: <the first 8 characters of the ORIGINAL key, before redaction>

The key_id must be only that 8-character prefix: the full original key must
appear nowhere in the report, and nowhere in the tree once you are done.

Verify with `python3 check.py` and iterate until it passes. `check.py` and
`README.md` must stay byte-identical.
