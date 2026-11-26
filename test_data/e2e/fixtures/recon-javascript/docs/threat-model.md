# Threat model

The security review tracks the categories below. When a finding falls into one of them, name it
with the CWE identifier given here.

| Category | CWE |
| --- | --- |
| Cross-site scripting | CWE-79 |
| Cross-site request forgery (CSRF) | CWE-352 |
| Insufficiently protected credentials | CWE-522 |
| Use of hard-coded credentials | CWE-798 |
| Open redirect | CWE-601 |
| Path traversal | CWE-22 |

Out of scope for this release: third party widgets, the portal API behind `/api`, and browser
extensions.
