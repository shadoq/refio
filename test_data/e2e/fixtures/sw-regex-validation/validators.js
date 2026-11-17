'use strict';

/**
 * validators.js - field validation shared by the workshop signup form and the
 * admin CSV importer.
 *
 * Every validator takes the raw input value and returns a boolean. The form
 * layer trims nothing and shows the generic error message when a validator
 * says no; validateSignup() aggregates the required fields.
 */

// Email: local part, @, domain. Dots and dashes are allowed in the domain.
const EMAIL_PATTERN = /[a-z0-9._%+-]+@[a-z0-9.-]+/i;

// Date of birth as written on the paper form: MM/DD/YYYY.
const DATE_PATTERN = /[0-9]{2}\/[0-9]{2}\/[0-9]{4}/;

// URL slug: lowercase words separated by single dashes.
const SLUG_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;

// Six-digit hex colour, as typed by the design team.
const HEX_PATTERN = /^#[0-9a-f]{6}$/i;

function isNonEmptyString(value) {
  return typeof value === 'string' && value.trim().length > 0;
}

function isValidEmail(value) {
  if (!isNonEmptyString(value)) {
    return false;
  }
  return EMAIL_PATTERN.test(value);
}

function isValidDate(value) {
  if (!isNonEmptyString(value)) {
    return false;
  }
  return DATE_PATTERN.test(value);
}

function isValidSlug(value) {
  if (!isNonEmptyString(value)) {
    return false;
  }
  return SLUG_PATTERN.test(value);
}

function isValidHexColor(value) {
  if (!isNonEmptyString(value)) {
    return false;
  }
  return HEX_PATTERN.test(value);
}

/**
 * Aggregate check for the signup form. Returns an errors object keyed by
 * field name; it is empty when every required field is valid.
 */
function validateSignup(fields) {
  const errors = {};
  if (!isValidEmail(fields.email)) {
    errors.email = 'enter a valid email address';
  }
  if (!isValidDate(fields.birthDate)) {
    errors.birthDate = 'enter a valid date (MM/DD/YYYY)';
  }
  if (fields.slug && !isValidSlug(fields.slug)) {
    errors.slug = 'slugs are lowercase words joined by single dashes';
  }
  return errors;
}

module.exports = {
  isValidEmail,
  isValidDate,
  isValidSlug,
  isValidHexColor,
  validateSignup,
};
