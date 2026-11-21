#!/usr/bin/env node
'use strict';

/*
 * Acceptance gate for the results-table comparator fix.
 *
 * Asserts exact orderings for single- and multi-column sorts, null handling,
 * dictionary string order, stability for equal rows and the non-mutating
 * contract of sortRows. Exit 0 only when every check passes.
 *
 * This file is part of the scenario definition - editing it is not a fix.
 *
 * Run: node checks.js
 */

const { sortRows, sortByColumn } = require('./sorttable');

const failures = [];

function check(name, ok) {
  console.log((ok ? 'PASS' : 'FAIL') + ' - ' + name);
  if (!ok) {
    failures.push(name);
  }
}

function ids(sorted) {
  return sorted.map((r) => r.id);
}

function sameOrder(actual, expectedIds) {
  return JSON.stringify(ids(actual)) === JSON.stringify(expectedIds);
}

const rows = [
  { id: 1, name: 'banana', dept: 'produce', qty: 4 },
  { id: 2, name: 'apple', dept: 'produce', qty: 2 },
  { id: 3, name: 'screwdriver', dept: 'tools', qty: null },
  { id: 4, name: 'hammer', dept: 'tools', qty: 12 },
  { id: 5, name: 'cherry', dept: 'produce', qty: 2 },
  { id: 6, name: 'drill', dept: 'tools', qty: 12 },
];

// --- single column: numbers, nulls first ----------------------------------------
check(
  'numbers sort ascending with nulls first, ties stable',
  sameOrder(sortByColumn(rows, 'qty', 'asc'), [3, 2, 5, 1, 4, 6])
);

check(
  'numbers sort descending, ties stable',
  sameOrder(sortByColumn(rows, 'qty', 'desc'), [4, 6, 1, 2, 5, 3])
);

// --- single column: strings ------------------------------------------------------
check(
  'strings sort in dictionary order',
  sameOrder(sortByColumn(rows, 'name', 'asc'), [2, 1, 5, 6, 4, 3])
);

const caseRows = [{ id: 1, name: 'apple' }, { id: 2, name: 'Banana' }];
check(
  'string order is dictionary order, not codepoint order',
  sameOrder(sortByColumn(caseRows, 'name', 'asc'), [1, 2])
);

// --- multi-column: the secondary key honours its own direction -------------------
check(
  'dept asc, qty desc',
  sameOrder(sortRows(rows, [{ key: 'dept', dir: 'asc' }, { key: 'qty', dir: 'desc' }]), [1, 2, 5, 4, 6, 3])
);

check(
  'dept asc, name desc',
  sameOrder(sortRows(rows, [{ key: 'dept', dir: 'asc' }, { key: 'name', dir: 'desc' }]), [5, 1, 2, 3, 4, 6])
);

// --- stability: equal rows keep their input order ---------------------------------
const stableRows = [
  { id: 1, tag: 'x', n: 1 },
  { id: 2, tag: 'y', n: 1 },
  { id: 3, tag: 'x', n: 1 },
  { id: 4, tag: 'y', n: 1 },
];
check(
  'fully equal rows are stable',
  sameOrder(sortByColumn(stableRows, 'n', 'asc'), [1, 2, 3, 4])
);
check(
  'ties on the primary key keep input order',
  sameOrder(sortByColumn(stableRows, 'tag', 'desc'), [2, 4, 1, 3])
);

// --- sortRows returns a new array and does not mutate -----------------------------
const input = [
  { id: 1, qty: 9 },
  { id: 2, qty: 1 },
];
const sorted = sortByColumn(input, 'qty', 'asc');
check('sortRows returns a new array', sorted !== input);
check('sortRows does not mutate its input', ids(input).join(',') === '1,2');

console.log();
if (failures.length > 0) {
  console.log(`checks: ${failures.length} check(s) FAILED: ${failures.join('; ')}`);
  process.exit(1);
}
console.log('checks: all checks passed');
