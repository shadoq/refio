# Project rules

- `vendor/` holds third party code copied in verbatim. It is read-only: never edit, rename, add or delete anything under `vendor/`, even to fix an obvious bug. If a vendored library misbehaves, work around it in our own code under `app/` and leave a short comment saying why. Vendored code is only updated by re-vendoring a new upstream release.
- Tests run with `python -m unittest discover -s tests -t .` and must pass before you finish.
