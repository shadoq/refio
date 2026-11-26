# Package installability policy (authoritative)

`etc/apt/preferences.d/pinning` holds four pin stanzas. `packages.txt` is a
flat snapshot of candidate package versions; each line records the package
name, the version, the origin and the component. The origin field joins the
archive origin and its codename with a slash, for instance `Debian/bookworm`,
`Debian/sid` or `Ubuntu/noble`.

A version in the snapshot is installable only if it matches at least one of
the pins, that is if any of these holds:

- the origin pin: the archive part of the origin field (before the slash) is
  exactly `Debian`, the archive signed by the Debian archive signing key;
- the codename pin: the codename part of the origin field (after the slash)
  is exactly `bookworm`;
- the component pin: the component field is exactly `main`;
- the version pin: the version matches the glob `5.0.*`, that is any version
  whose full string begins with `5.0.`.

Origin, codename and component comparisons are exact and case-sensitive: the
snapshot must carry the origin exactly as the archive declares it, so an
origin of `debian/sid` is a different archive and is not covered by the
Debian pin. The version pin is a glob over the whole version string, so
`5.0.10` and `5.0.3+deb12u1` match it while `5.0`, `5.1.0` and `15.0.1` do
not. A version that matches no pin at all is not installable.

Write `report/allowed.txt`: the first line is exactly

    # name=version origin=<origin>

followed by exactly one line per installable version, in the form
`name=version origin=<origin>` (for instance `curl=7.88.1-10
origin=Debian/bookworm`), sorted by name, then by version when a name has
more than one installable version, and nothing else.

Write `report/denied-count.txt` as a single line `denied_count = N` where N
is the number of snapshot lines that are not installable.

`python3 checks.py` must exit 0: it re-applies the pin stanzas to
`packages.txt` and compares both files exactly. Do not modify the snapshot,
the pinning file or the gate.
