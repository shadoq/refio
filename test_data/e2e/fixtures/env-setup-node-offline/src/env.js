'use strict';
const fs = require('node:fs');
const path = require('node:path');

// Loads KEY=VALUE lines from the project .env into process.env.
// Variables that are already set in the environment are left alone.
function loadEnv() {
  const file = path.join(__dirname, '..', '.env');
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const text = line.trim();
    if (!text || text.startsWith('#')) continue;
    const eq = text.indexOf('=');
    if (eq < 1) continue;
    const key = text.slice(0, eq).trim();
    const value = text.slice(eq + 1).trim();
    if (!(key in process.env)) process.env[key] = value;
  }
}

module.exports = { loadEnv };
