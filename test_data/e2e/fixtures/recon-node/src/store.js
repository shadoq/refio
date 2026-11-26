'use strict';

const fs = require('node:fs');
const path = require('node:path');

// A stored original is addressed by its file name: letters, digits, dot, dash and underscore.
const NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$/;

/**
 * Path of a stored original. Since 2.1.0 a name is accepted only when it is a plain file name,
 * so it cannot leave the upload directory.
 */
function originalPath(name, uploadDir = process.env.UPLOAD_DIR ?? 'uploads') {
  if (typeof name !== 'string' || name.includes('..') || !NAME.test(name)) return null;
  return path.join(uploadDir, name);
}

function invalidName() {
  return Object.assign(new Error('invalid original name'), { status: 400 });
}

function readOriginal(name, uploadDir) {
  const target = originalPath(name, uploadDir);
  if (!target) return Promise.reject(invalidName());
  return fs.promises.readFile(target).catch((error) => {
    throw error.code === 'ENOENT' ? Object.assign(new Error('no such original'), { status: 404 }) : error;
  });
}

module.exports = { originalPath, readOriginal };
