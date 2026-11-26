# Threat model

The security review tracks the categories below. When a finding falls into one of them, name it
with the CWE identifier given here.

| Category | CWE |
| --- | --- |
| OS command injection | CWE-78 |
| SQL injection | CWE-89 |
| Weak storage of passwords | CWE-916 |
| Use of hard-coded credentials | CWE-798 |
| Cross-site scripting | CWE-79 |
| Path traversal | CWE-22 |
| Server-side request forgery | CWE-918 |
| Insecure deserialization | CWE-502 |

Out of scope for this release: denial of service, rate limiting and the file format parsers in
`export.py` that are delegated to external tools.
