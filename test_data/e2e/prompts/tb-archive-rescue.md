A failed deploy mangled the orders service configuration (the drifted mirror is
`etc/app.conf`). The pristine backup survives inside the rescue payload
`rescue/payload.b64`, which is nested: base64-encoded ZIP archive, containing a
`backup-2026-05-30.tar.gz` tarball, containing `conf/app.conf.pristine` (plus a
manifest). The full layer map is in `README.md`.

Your task: decode every layer in the right order and restore the pristine configuration
byte-exactly to `restored/app.conf` (use any tool: python, base64/unzip/tar, ...).
`python3 checks.py` must exit 0 - it checks the sha256 digest of your restored file, so
extra bytes, wrong layer order or a copy of the drifted file all fail. Do not modify the
payload, the mirror or the gate.
