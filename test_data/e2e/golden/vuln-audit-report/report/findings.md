# Security audit - Notes service

## Summary

The service performs no input handling on either query path and ships a hardcoded credential.
All three findings are exploitable by any office-network user who can reach the service; two of
them expose data outside the notes store.

## Findings

### 1. SQL injection in the note search

- **Severity:** Critical
- **Location:** `search_notes` in `src/app.py`
- **CWE:** CWE-89 (SQL Injection)
- **Attack path:** `GET /notes?q=' UNION SELECT ...` - the search term is interpolated directly
  into the LIKE clause of the query string, so a crafted term can change the statement, read
  other tables or corrupt data.
- **Remediation:** Use a parameterized query (`... title LIKE ?` with the term passed separately).

### 2. Path traversal in the note download

- **Severity:** High
- **Location:** `read_note` in `src/app.py`
- **CWE:** CWE-22 (Path Traversal)
- **Attack path:** `GET /download?name=../../db.py` - the caller-supplied name is joined onto
  NOTES_DIR without any containment check, so `../` segments escape the notes directory and the
  endpoint returns any file the service user can read.
- **Remediation:** Normalize the resolved path and reject anything that is not inside NOTES_DIR.

### 3. Hardcoded API key

- **Severity:** High
- **Location:** module-level `API_KEY` in `src/app.py`, checked by `check_api_key`
- **CWE:** CWE-798 (Use of Hard-coded Credentials)
- **Attack path:** anyone with access to the source or a copy of it can read the key and call
  the authenticated endpoints; the key cannot be rotated without a code change and deploy.
- **Remediation:** Load the key from an environment variable or a secret store at runtime.
