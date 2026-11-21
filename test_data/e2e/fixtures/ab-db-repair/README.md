# Repair run - crm.db

- `crm.db` - the CRM (customers, orders, order_items) after a bad import: orphan item
  rows, an orphan order, duplicate and NULL-email accounts, comma-decimal prices stored
  as TEXT.
- `docs/invariants.md` - the authoritative invariants and the merge rules.
- `checks.py` - the acceptance gate (do not modify it).

Repair the database in place so every invariant holds.
