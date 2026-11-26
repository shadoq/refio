# Reconnaissance report - image-resizer

Scope: the source in `src` and the staging instance.

## Passive reconnaissance (source review)

passive_finding = CWE-918 | src/source.js | fetchByUrl
passive_finding = CWE-1321 | src/options.js | applyParams
passive_finding = CWE-1333 | src/validate.js | isSize

- **CWE-918 Server-side request forgery** (`fetchByUrl` in `src/source.js`): the address in the `src` parameter is requested as given, with no allow list and no check of where it points, so a caller can reach hosts inside the network

- **CWE-1321 Prototype pollution** (`applyParams` in `src/options.js`): the keys of a JSON body are assigned recursively without refusing `__proto__`, so a caller can write to Object.prototype

- **CWE-1333 Inefficient regular expression complexity** (`isSize` in `src/validate.js`): the size check nests quantifiers, `(\d+)+`, so a string of digits without the `x` costs exponential time

- **CWE-330 Use of insufficiently random values** (`issueLink` in `src/signing.js`): the signature that makes a feed link valid is drawn from Math.random, which is predictable

## Active reconnaissance (staging instance)

instance_id = a1b2c3d4e5f6
staging_version = 1.9.3
source_version = 2.1.0
undocumented_endpoint = /internal/diagnostics
exposed_backup = image-resizer-1.9.3-2026-11-23.tar.gz
traversal_leak = rz-dep-3e81c5a2f7d4

- `/health` answers `{"service": "image-resizer", "version": "1.9.3", ...}` with a fresh `instance` value, so staging runs behind the source (2.1.0).
- `/robots.txt` disallows `/internal/` and `/exports/`; `/internal/diagnostics` is the diagnostics page that 2.0.0 removed and answers 200 on staging, `/exports/` lists three index backups of which the 2026-11-23 one is the newest.
- `/originals?name=..` lists the parent directory and names `resizer.env`; `/originals?name=../resizer.env` returns the deployment settings with the deploy token on 1.9.3, although 2.1.0 fixed exactly that in `originalPath`: the fix has not been deployed.
- `/ops` answers 403 from the network policy and is out of scope.
