# Reconnaissance report - notes-api

Scope: the source in `src/notes_api` and the staging instance.

## Passive reconnaissance (source review)

passive_finding = CWE-78 | src/notes_api/export.py | export_notes
passive_finding = CWE-89 | src/notes_api/db.py | search_notes
passive_finding = CWE-916 | src/notes_api/auth.py | hash_password
passive_finding = CWE-798 | src/notes_api/config.py | load_settings

- **CWE-78 OS command injection** (`export_notes` in `src/notes_api/export.py`): the converter is started through a shell with a string-built command; the owner's name reaches it

- **CWE-89 SQL injection** (`search_notes` in `src/notes_api/db.py`): the search pattern is interpolated into the SQL text with % formatting instead of a bound parameter

- **CWE-916 Weak storage of passwords** (`hash_password` in `src/notes_api/auth.py`): passwords are stored as an unsalted MD5 digest

- **CWE-798 Hard-coded credential** (`load_settings` in `src/notes_api/config.py`): a live API key is compiled into the settings loader as the fallback

## Active reconnaissance (staging instance)

instance_id = 0f0e0d0c0b0a
staging_version = 1.9.3
source_version = 2.1.0
undocumented_endpoint = /internal/env
exposed_backup = notes_api-1.9.3-2026-11-02.tar.gz
traversal_leak = stg-dep-7f3a9c0b1d2e

- `/health` answers `{"service": "notes-api", "version": "1.9.3", ...}` with a fresh `instance` value, so staging runs three releases behind the source (2.1.0).
- `/robots.txt` disallows `/internal/` and `/backups/`; `/internal/env` is the diagnostics page that 2.0.0 removed and answers 200 on staging, `/backups/` lists two archives of which the 2026-11-02 one is the newest.
- `/attachments?name=../deploy.env` still returns the deployment settings on 1.9.3, although 2.1.0 fixed exactly that in `fetch_attachment`: the fix has not been deployed.
- `/admin` answers 403 from the network policy and is out of scope.
