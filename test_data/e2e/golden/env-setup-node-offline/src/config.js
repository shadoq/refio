'use strict';
const { loadEnv } = require('./env');

function readConfig() {
  loadEnv();
  const name = process.env.APP_NAME;
  if (!name) throw new Error('missing setting APP_NAME');
  const port = Number(process.env.PORT);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('invalid port: ' + process.env.PORT);
  }
  return { name, port, greeting: process.env.GREETING || '' };
}

module.exports = { readConfig };
