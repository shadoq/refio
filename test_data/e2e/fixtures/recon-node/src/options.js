'use strict';

// Limits every preset carries. A query string may lower them (mergeLimits) but not add fields.
const LIMITS = { maxWidth: 4096, maxHeight: 4096, maxBytes: 8 * 1024 * 1024 };
const BLOCKED_KEYS = new Set(['__proto__', 'constructor', 'prototype']);

const PRESETS = {
  thumbnail: { ...LIMITS, format: 'jpeg', quality: 80, crop: 'center' },
  preview: { ...LIMITS, format: 'webp', quality: 75, crop: 'fit' },
};

/** Copies the numeric limits the caller is allowed to lower, and nothing else. */
function mergeLimits(target, source) {
  const merged = { ...target };
  for (const key of Object.keys(source)) {
    if (BLOCKED_KEYS.has(key) || !Object.hasOwn(LIMITS, key)) continue;
    const value = Number(source[key]);
    if (Number.isInteger(value) && value > 0) merged[key] = Math.min(value, LIMITS[key]);
  }
  return merged;
}

function assignDeep(target, source) {
  for (const key of Object.keys(source)) {
    const value = source[key];
    if (value !== null && typeof value === 'object') {
      if (target[key] === null || typeof target[key] !== 'object') target[key] = {};
      assignDeep(target[key], value);
    } else {
      target[key] = value;
    }
  }
  return target;
}

/**
 * Options of a `POST /resize` call. The body may override any field of the preset, including
 * the nested `output` block, so the two are merged recursively.
 */
function applyParams(preset, params) {
  const options = {};
  assignDeep(options, preset);
  assignDeep(options, params);
  return options;
}

module.exports = { PRESETS, applyParams, mergeLimits };
