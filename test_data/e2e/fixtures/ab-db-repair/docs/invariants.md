# Data invariants - crm.db (authoritative)

An import bug left the CRM in a state that violates every rule below. Repair the
database **in place** (delete/merge/normalize - any tool is fine):

1. **No orphan items** - every `order_items.order_id` must exist in `orders`. Orphan item
   rows are removed.
2. **No orphan orders** - every `orders.customer_id` must exist in `customers`. Orders
   whose customer is gone are removed (their items go with them).
3. **Emails are unique and never NULL** - duplicate accounts are **merged**: keep the
   account with the lowest id, re-point its duplicate's orders to it, drop the duplicate.
   A customer with a NULL email cannot be kept at all: remove it and its orders.
4. **Prices are numbers** - `order_items.unit_price` was imported as TEXT with comma
   decimals (`48,0` means 48.0). Every value must end up stored as a real number (dot
   decimals), without changing any amount.
5. **Money conservation** - after the repair, `ROUND(SUM(qty*unit_price), 2)` over
   `order_items` must equal exactly **877.0**. Money may not be created or destroyed by
   the repair.

`python3 checks.py` must exit 0 (it asserts each invariant against the database, plus the
digests above). Do not modify the docs or the gate.
