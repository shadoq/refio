# Threat model

The security review tracks the categories below. When a finding falls into one of them, name it
with the CWE identifier given here.

| Category | CWE |
| --- | --- |
| Out-of-bounds write | CWE-787 |
| Integer overflow or wraparound | CWE-190 |
| OS command injection | CWE-78 |
| Use of hard-coded credentials | CWE-798 |
| Path traversal | CWE-22 |
| Use of insufficiently random values | CWE-330 |
| Improper input validation of index (array subscript) | CWE-129 |

Out of scope for this release: denial of service, memory exhaustion, and the TCP stack itself.
