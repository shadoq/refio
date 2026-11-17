During an IR engagement you captured the workstation's DNS traffic to a text log:

- `capture/dns-session.log` - one line per DNS query, `IP client > resolver: id+ A? name.`
- `README.md` - the engagement note (what the analysts already noticed).
- `checks.py` - verification gate (it knows the expected digest, not the plaintext).

The analysts noticed an unusual burst of queries to `*.exfil.example.com`: names whose first
label is a three-digit number and whose second label looks like encoded data. One query uses
the first label `999` and looks like a terminal marker, not a data chunk.

Your task: reassemble the exfiltrated payload - order the data chunks by their sequence
number, join the encoded data, decode it (the character set of the data labels should tell
you the encoding) - and write the decoded plaintext as the only content of
`report/extracted.txt`. The payload is short; do not include query noise or marker labels in
the reconstruction. `python3 checks.py` must exit 0 (it hashes the file, so the exact
decoded bytes are required). Do not modify the capture, the gate or the README.
