'use strict';

const MAX_SIDE = 8192;

/** `640x480`: two decimal numbers, at most five digits each, both within the side limit. */
function parseDimensions(value) {
  const match = /^([0-9]{1,5})x([0-9]{1,5})$/.exec(String(value ?? '').trim());
  if (!match) return null;
  const width = Number(match[1]);
  const height = Number(match[2]);
  if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) return null;
  return { width, height };
}

/**
 * Older feed links write the size with a density suffix, such as `640x480@2x`. Only the shape
 * is checked here; parseDimensions does the range checks once the suffix is removed.
 */
function isSize(value) {
  return /^(\d+)+x(\d+)+(@[0-9]+)?$/.test(String(value ?? '').trim());
}

module.exports = { isSize, parseDimensions };
