#!/usr/bin/env node
'use strict';

/*
 * Acceptance gate for the cent-exact cart fix.
 *
 * Replays the three reports from finance (float drift on the subtotal,
 * per-unit rounding on the discount, drifted totals after VAT) plus the
 * cents-level accessors the receipt printer needs. Exit 0 only when every
 * check passes.
 *
 * This file is part of the scenario definition - editing it is not a fix.
 *
 * Run: node checks.js
 */

const { Cart } = require('./cart');

const failures = [];

function check(name, ok) {
  console.log((ok ? 'PASS' : 'FAIL') + ' - ' + name);
  if (!ok) {
    failures.push(name);
  }
}

// --- report 1: the classic drift on plain sums ----------------------------------
const drift = new Cart();
drift.add('penny-a', 0.1, 1).add('penny-b', 0.2, 1).add('penny-c', 0.3, 1);
check('the 0.1 + 0.2 + 0.3 cart sums to exactly 0.6', drift.subtotal() === 0.6);
check('the drift cart exposes integer cents', drift.subtotalCents() === 60);

const bigger = new Cart();
bigger.add('wire', 19.99, 3).add('plug', 5.49, 2);
check('a mixed cart sums to exactly 70.95', bigger.subtotal() === 70.95);

// --- report 2: the per-unit rounding on discounts --------------------------------
const discount = new Cart();
discount.add('cable', 10.05, 3);
// correct: round the LINE total once - 1005 * 3 * 0.9 = 2713.5 -> 2714 cents
check('the 10% discount on 3x 10.05 totals exactly 27.14',
  discount.discountedSubtotal(0.1) === 27.14);
check('the discounted line is exact in cents',
  discount.discountedSubtotalCents(0.1) === 2714);

// --- report 3: totals after VAT, exact to the cent -------------------------------
// net 2714 cents -> 2714 * 1.23 = 3338.22 -> 3338 cents = 33.38
check('the total with VAT is exactly 33.38', discount.total(0.1) === 33.38);
check('the total is exact in cents', discount.totalCents(0.1) === 3338);
check('the plain total (no discount) carries no drift',
  bigger.total() === 87.27 && bigger.totalCents() === 8727);

// --- the receipt printer contract -------------------------------------------------
check('formatCents renders 3338 as "33.38"', discount.formatCents(3338) === '33.38');
check('formatCents renders 5 as "0.05"', discount.formatCents(5) === '0.05');
check('formatCents renders 120 as "1.20"', discount.formatCents(120) === '1.20');

// --- edge cases -------------------------------------------------------------------
const empty = new Cart();
check('an empty cart has a zero subtotal', empty.subtotal() === 0);
check('an empty cart has zero cents', empty.totalCents() === 0);

console.log();
if (failures.length > 0) {
  console.log(`checks: ${failures.length} check(s) FAILED: ${failures.join('; ')}`);
  process.exit(1);
}
console.log('checks: all checks passed');
