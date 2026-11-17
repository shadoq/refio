# Rescue run - orders service configuration

The failed deploy mangled `/etc/app.conf` on the host (the mirror copy is in `etc/`).
The pristine backup survives inside a rescue payload the previous operator left in
`rescue/` - but it is **nested**:

1. `rescue/payload.b64` - base64-encoded ZIP archive,
2. the ZIP contains `backup-2026-05-30.tar.gz`,
3. the tarball contains `conf/app.conf.pristine` (the byte-exact old configuration)
   and `MANIFEST.txt`.

Your task: decode every layer and restore the pristine configuration **byte-exactly**
to `restored/app.conf`. `python3 checks.py` must exit 0 (it verifies the sha256 digest
of your restored file - partial or drifted content fails). Do not modify the payload,
the drifted mirror, the docs or the gate.
