## Results table sorts in the wrong order and jumps while paging

### Symptom

The results table is scrambled: ascending sorts come out mostly backwards,
descending is unpredictable, and the secondary column (applied inside each
department) ignores its own direction - a "dept asc, qty desc" sort lists the
lowest quantities first within a department. Rows with a null quantity land in
random places, and two rows that should compare equal sometimes swap between
requests, which support reads as "the table jumps while paging".

### How to reproduce

    node -e '
    const { sortByColumn } = require("./sorttable");
    const rows = [
      { id: 1, qty: 4 },
      { id: 2, qty: 2 },
      { id: 3, qty: null },
      { id: 4, qty: 12 },
    ];
    console.log(sortByColumn(rows, "qty", "asc").map((r) => r.id));
    // expected 3,2,1,4 - actual output is scrambled
    '

### Expected behaviour

The comparator must return a number (-1, 0 or 1) - never a boolean, which
`Array.prototype.sort` cannot interpret. Every column in the spec must honour
its own direction, null and undefined values must sort first, numbers must
compare numerically, strings must compare in dictionary order (`localeCompare`,
so `apple` sorts before `Banana`), and rows that compare equal must keep their
input order. `sortRows` keeps returning a new array and must not mutate its
input.

### Your task

1. Reproduce the scrambled orderings in `sorttable.js` and identify why the
   comparator's boolean returns and the primary-column-only direction handling
   break the sort.
2. Fix `sorttable.js` in place: extract a numeric value comparison for cell
   values, apply each column's own direction to its result, and keep the
   `sortRows` / `sortByColumn` API unchanged.
3. Run the gate: `node checks.js` - it asserts exact orderings for single- and
   multi-column sorts, null handling, dictionary order, stability and the
   non-mutating contract, and must pass.

Do not modify `checks.js` or the README: the fix belongs in `sorttable.js`.
