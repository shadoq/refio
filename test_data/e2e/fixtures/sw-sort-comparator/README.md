# results table sorter

`sorttable.js` sorts result rows by one or more columns:
`sortRows(rows, [{ key: 'dept', dir: 'asc' }, { key: 'name', dir: 'desc' }])`
returns a new array and leaves the input untouched. Value ordering: null and
undefined sort first, numbers compare numerically, everything else compares as
a string; rows that compare equal keep their input order.

Run the acceptance gate with:

    node checks.js
