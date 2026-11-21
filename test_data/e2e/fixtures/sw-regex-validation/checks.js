#!/usr/bin/env node
'use strict';

/*
 * Acceptance gate for the signup validator fix.
 *
 * Replays the valid and invalid tables from the issue (email and date of
 * birth) plus the untouched slug and hex-colour validators, and the aggregated
 * signup check. Exit 0 only when every check passes.
 *
 * This file is part of the scenario definition - editing it is not a fix.
 *
 * Run: node checks.js
 */

const {
  isValidEmail,
  isValidDate,
  isValidSlug,
  isValidHexColor,
  validateSignup,
} = require('./validators');

const failures = [];

function check(name, ok) {
  console.log((ok ? 'PASS' : 'FAIL') + ' - ' + name);
  if (!ok) {
    failures.push(name);
  }
}

function checkTable(name, fn, table, expected) {
  const bad = table.filter((value) => fn(value) !== expected);
  check(name, bad.length === 0);
  for (const value of bad) {
    console.log(`        ${JSON.stringify(value)} validated as ${!expected}`);
  }
}

// --- email ----------------------------------------------------------------------
checkTable(
  'valid email addresses are accepted',
  isValidEmail,
  ['user@example.com', 'first.last@sub.domain.co.uk', 'a+b@c.io', 'o@x.museum'],
  true
);

checkTable(
  'addresses with a second @ are rejected',
  isValidEmail,
  ['a@b@c', 'user@name@example.com'],
  false
);

checkTable(
  'addresses with surrounding junk or no TLD are rejected',
  isValidEmail,
  ['user@example.com<script>', 'user@example.com extra', 'user@localhost'],
  false
);

checkTable(
  'structurally broken addresses are rejected',
  isValidEmail,
  ['no-at-sign.net', 'user@', '@example.com'],
  false
);

// --- date ------------------------------------------------------------------------
checkTable(
  'valid dates are accepted',
  isValidDate,
  ['01/31/2023', '1/2/2023', '12/31/1999', '02/29/2020', '07/04/1776'],
  true
);

checkTable(
  'months outside 01-12 are rejected',
  isValidDate,
  ['13/01/2023', '00/05/2023', '113/45/2023'],
  false
);

checkTable(
  'days outside 01-31 are rejected',
  isValidDate,
  ['01/32/2023', '12/00/2023', '113/45/2023'],
  false
);

checkTable(
  'dates with surrounding junk or the wrong shape are rejected',
  isValidDate,
  ['01/01/2023 extra', '2023-01-01', '01/01/23'],
  false
);

// --- the untouched validators keep working ----------------------------------------
checkTable('slugs still validate', isValidSlug, ['workshop-2024', 'a'], true);
checkTable('broken slugs are still rejected', isValidSlug, ['-lead', 'double--dash', 'UPPER'], false);
checkTable('hex colours still validate', isValidHexColor, ['#4a5568', '#FFFFFF'], true);
checkTable('broken hex colours are still rejected', isValidHexColor, ['#fff', '4a5568', '#12345'], false);

// --- the signup form aggregates both fixes -----------------------------------------
const errors = validateSignup({
  email: 'a@b@c',
  birthDate: '13/32/2023',
  slug: 'workshop-2024',
});
check(
  'the signup form flags both reported fields',
  Boolean(errors.email) && Boolean(errors.birthDate) && !errors.slug
);

const clean = validateSignup({ email: 'user@example.com', birthDate: '02/29/2020' });
check('a clean signup passes untouched', Object.keys(clean).length === 0);

console.log();
if (failures.length > 0) {
  console.log(`checks: ${failures.length} check(s) FAILED: ${failures.join('; ')}`);
  process.exit(1);
}
console.log('checks: all checks passed');
