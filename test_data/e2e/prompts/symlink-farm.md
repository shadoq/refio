# Link farm hand-off

Someone hand-edited the release slot manifest (`releases/links.txt`) during the last
release train and it no longer matches the authoritative matrix
(`docs/release-matrix.md`). Four slots are wrong. The pipeline deploys exactly what the
manifest says, so prod is currently serving a stale build.

Your task: repair `releases/links.txt` so every slot matches the matrix exactly (keep the
file's comment and one-slot-per-line `slot -> version` shape). `python3 checks.py` must
exit 0 - it parses your manifest and cross-checks it against the matrix and the version
directories. Do not edit the matrix, the gate, the version dirs or the README.
