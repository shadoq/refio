# Threat model

The security review tracks the categories below. When a finding falls into one of them, name it
with the CWE identifier given here.

| Category | CWE |
| --- | --- |
| Path traversal | CWE-22 |
| OS command injection | CWE-78 |
| Improper verification of cryptographic signature | CWE-347 |
| Error message containing sensitive information | CWE-209 |
| Use of hard-coded credentials | CWE-798 |
| Use of insufficiently random values | CWE-330 |

Out of scope for this release: the subscriber endpoints themselves, the paging tool and the
chat CLI.
