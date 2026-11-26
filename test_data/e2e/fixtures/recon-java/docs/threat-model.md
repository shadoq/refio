# Threat model

The security review tracks the categories below. When a finding falls into one of them, name it
with the CWE identifier given here.

| Category | CWE |
| --- | --- |
| SQL injection | CWE-89 |
| Insecure deserialization | CWE-502 |
| XML external entity reference (XXE) | CWE-611 |
| Use of hard-coded credentials | CWE-798 |
| Path traversal | CWE-22 |
| Cross-site scripting | CWE-79 |
| Server-side request forgery | CWE-918 |

Out of scope for this release: denial of service, rate limiting, and the JDBC driver itself.
