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
 * Compare two cell values: returns -1, 0 or 1 (nil first, numbers numeric,
 * everything else by localeCompare).
 */
function compareValues(av, bv) {
  const aNil = av === null || av === undefined;
  const bNil = bv === null || bv === undefined;
  if (aNil || bNil) {
    if (aNil && bNil) return 0;
    return aNil ? -1 : 1;
  }
  if (typeof av === 'number' && typeof bv === 'number') {
    return av < bv ? -1 : av > bv ? 1 : 0;
  }
  const r = String(av).localeCompare(String(bv));
  return r < 0 ? -1 : r > 0 ? 1 : 0;
}

/**
 * Build a comparator for a column spec; each column applies its own direction.
 */
function comparatorFor(spec) {
  return function (a, b) {
    for (let i = 0; i < spec.length; i += 1) {
      const column = spec[i];
      const r = compareValues(a[column.key], b[column.key]);
      if (r !== 0) {
        return column.dir === 'desc' ? -r : r;
      }
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
