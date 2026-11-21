`report.py` has grown a block of date helpers (`parse_iso_date`, `days_between`, `is_weekend`, `business_days_between`) that have nothing to do with invoices, and we want to reuse them elsewhere. Move those four functions into a new module `datecalc.py`, without changing their behaviour.

Existing code does `from report import business_days_between` and similar, so those names must keep importing from `report` and must be the same functions. `report.py` must no longer define them itself. The invoice functions stay where they are.

`python check_refactor.py` runs the tests in `test_report.py` and checks the new structure; it must exit 0. Do not edit the tests or the check script.
