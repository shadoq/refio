`StockLedger.build_statement` in `stock_ledger.py` has grown to over a hundred lines and nobody wants to touch it. Please refactor it into smaller private helper methods on the same class, each doing one step, so that `build_statement` itself reads as a short outline.

The behaviour must not change at all: same output, same arguments, same errors. Leave every other method in the file exactly as it is, and do not shorten or summarise any code with placeholder comments. Write out the full file content you change.

`python checks/check_refactor.py` checks the structure and then runs the unit tests in `test_stock_ledger.py`; it must exit 0. Do not edit the tests or anything under `checks/`.
