`src/com/example/inventory/InventoryService.java` is the service layer behind the warehouse
kiosk, written in defensive-null style: `findItem` returns null, every caller compares
against null, `getStockLabel` stacks a null check on nested ternaries, and the aggregates
are hand-rolled for-loops. The team has standardized on `Optional` and streams, so the null
noise has to go.

Your task:

1. Change the lookups in `InventoryService.java` to return `Optional<Item>`: `findItem`
   guards the nullable scanner input at the entry point with `Optional.ofNullable`, and
   `mostExpensive` returns an empty Optional when nothing is stocked.
2. Rebuild `getStockLabel` on `Optional.map(...)` / `orElse(...)` instead of the null check
   and the nested ternaries, producing the exact same three output strings as before.
3. Replace the remaining loops with stream pipelines: `filter` + `findFirst` for the sku
   lookup, `mapToLong` + `sum` for the total stock value, `filter` + `map` + `collect` for
   the category name list, and `max` for the most expensive item.
4. Keep the observable behavior of every public method (same results for the same items);
   afterwards no reference anywhere in the file may be compared against null.

Do not modify the README: the change belongs in
`src/com/example/inventory/InventoryService.java`.
