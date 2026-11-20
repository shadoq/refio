Three modules hard-code their settings: `app/db.py` (database URL), `app/mailer.py` (SMTP host and password) and `app/main.py` (debug flag).

Plan moving all of them to environment variables read in one place, a new `app/settings.py`, with safe defaults for local work (but no default for the SMTP password). Document the variables in a new `.env.example`.

New files may be only `app/settings.py`, `.env.example` and files under `tests/`.

Do not change any file: this is planning only.

Answer with the plan as a numbered list (`1.`, `2.`, ...), one step per item, in the order the work must be done. In every step name the exact files it touches. Name only files that exist in the project, or new files in the places listed above.
