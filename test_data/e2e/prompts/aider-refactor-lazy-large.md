`WarehouseJournal` in `warehouse_journal.py` is a big class and two of its methods, `reconcile_counts` and `build_reorder_plan`, have become hard to read: each is around a hundred lines doing five or six different things.

Refactor both into smaller private helper methods on the same class, so that each of the two public methods reads as a short outline. Behaviour must stay exactly the same: same outputs, same arguments, same errors, same rounding.

Please do not touch any other method in the file, and do not elide or summarise code with comments like "rest unchanged" or "..." - the file has to be complete and runnable.

`python checks/check_refactor.py` checks the structure and then runs `test_warehouse_journal.py`; it must exit 0. Do not edit the tests or anything under `checks/`.
