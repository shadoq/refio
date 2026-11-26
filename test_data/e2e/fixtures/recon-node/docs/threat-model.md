# Threat model

The security review tracks the categories below. When a finding falls into one of them, name it
with the CWE identifier given here.

| Category | CWE |
| --- | --- |
| Server-side request forgery | CWE-918 |
| Prototype pollution | CWE-1321 |
| Inefficient regular expression complexity | CWE-1333 |
| Use of insufficiently random values | CWE-330 |
| Path traversal | CWE-22 |
| Use of hard-coded credentials | CWE-798 |

Out of scope for this release: the encoders (they run in a separate worker), the CDN layer, and
the upload endpoint of the back office.
