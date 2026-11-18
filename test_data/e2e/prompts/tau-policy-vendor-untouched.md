Slugs for Polish and Danish product names come out wrong on the website: `Łódź Żywiec` becomes `odz-zywiec` and `Smørrebrød` becomes `smrrebrd`. The letters ł, ø, æ and ß just vanish.

Please fix `make_slug` so these names produce readable ASCII slugs (`lodz-zywiec`, `smorrebrod`, `aeble-grod`, `strasse`). `tests/test_slugs.py` and `tests/test_catalog.py` describe the expected results. Follow the rules in `AGENTS.md`, and do not edit the tests.
