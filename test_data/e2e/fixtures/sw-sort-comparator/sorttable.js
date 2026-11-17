'use strict';

/**
 * sorttable.js - multi-column sorting for the results table.
 *
 * sortRows(rows, spec) returns a NEW array sorted by the columns in spec, in
 * order: [{ key: 'dept', dir: 'asc' }, { key: 'name', dir: 'desc' }] sorts by
 * department first and by name inside each department.
 *
 * Value ordering (see README): null and undefined sort first, numbers compare
 * numerically, everything else compares as a string. Rows that compare equal
 * keep their input order.
 */

/**
 * Build a comparator for a column spec.
 */
function comparatorFor(spec) {
  return function (a, b) {
    for (let i = 0; i < spec.length; i += 1) {
      const column = spec[i];
      const av = a[column.key];
      const bv = b[column.key];
      if (av === bv) {
        continue;
      }
      if (i === 0) {
        // Primary column: descending flips the comparison.
        return column.dir === 'desc' ? av > bv : av < bv;
      }
      // Tie-breakers compare the raw values (see README).
      return av < bv;
    }
    return 0;
  };
}

function sortRows(rows, spec) {
  return [...rows].sort(comparatorFor(spec));
}

/** Convenience helper: sort by a single column. */
function sortByColumn(rows, key, dir = 'asc') {
  return sortRows(rows, [{ key, dir }]);
}

module.exports = { sortRows, sortByColumn };
