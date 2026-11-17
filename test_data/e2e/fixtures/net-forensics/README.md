# IR note - workstation DNS capture

The analysts pulled the workstation's DNS traffic during the suspected compromise window
(`capture/dns-session.log`, one line per query). Most traffic is the usual internal noise
(`*.internal` names), but there is a burst of `A?` queries toward `exfil.example.com`:

- first label: a three-digit number (`001`, `002`, ...),
- second label: 6 characters from a restricted alphabet (lowercase letters and digits - it
  is not hex),
- a single `999.end.exfil.example.com` query at the end.

The working theory is DNS tunnelling: the beacon split a small payload into chunk labels and
sent them as subdomain lookups, sequencing them in the first label and marking the end with
the 999 query. Reconstructing it is on us.
