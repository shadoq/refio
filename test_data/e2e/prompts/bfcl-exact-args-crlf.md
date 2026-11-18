Invoice lines with VAT come out one grosz too low on exact halves: 2.50 net at 5% should have VAT of 0.13, but `faktura.py` gives 0.12. We use commercial rounding (halves always go up). Please fix the rounding in `faktura.py`.

This file is read by an old Windows invoicing tool that is picky about its format, so change only what is strictly needed and leave everything else in the file exactly as it is, including all Polish texts. `python checks/check_bytes.py` runs the tests and verifies the file; it must exit 0. Do not edit anything under `checks/` or `tests/`.
