We upgraded our internal `recordio` library from 1.x to 2.x. It now lives in `recordio.py` at the project root and the old 1.x functions are gone, so every module in `app/` that used them fails at import time. There are six modules: importer, inventory, orders, reports, exporter and audit.

Please migrate all of them to the 2.x API. The notes are in `MIGRATION.md` (and `CHANGELOG.md`). The public functions in `app/` must keep their signatures and their observable behaviour exactly as documented in their docstrings.

Done means `python -m unittest discover -s tests` exits 0. Do not edit anything under `tests/`, and do not edit `recordio.py` (it is vendored from another team, so no compatibility shims in it).

This is a multi-step job; work through the modules one by one and run the tests after each.
