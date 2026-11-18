'use strict';
// Verifies that the service can be set up and started from the repository alone, with no network.
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const net = require('node:net');
const path = require('node:path');

const root = __dirname;

function fail(message) {
  console.error('FAIL: ' + message);
  process.exit(1);
}

function parseEnv(text) {
  const out = {};
  for (const line of text.split(/\r?\n/)) {
    const t = line.trim();
    if (!t || t.startsWith('#')) continue;
    const eq = t.indexOf('=');
    if (eq > 0) out[t.slice(0, eq).trim()] = t.slice(eq + 1).trim();
  }
  return out;
}

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address();
      srv.close(() => resolve(port));
    });
    srv.on('error', reject);
  });
}

function getHealth(port) {
  return new Promise((resolve, reject) => {
    const req = http.get({ host: '127.0.0.1', port, path: '/health', timeout: 1000 }, (res) => {
      let body = '';
      res.on('data', (chunk) => (body += chunk));
      res.on('end', () => {
        try {
          resolve(JSON.parse(body));
        } catch (err) {
          reject(err);
        }
      });
    });
    req.on('error', reject);
    req.on('timeout', () => req.destroy(new Error('timeout')));
  });
}

async function waitForHealth(port, child) {
  const deadline = Date.now() + 6000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error('the process exited with code ' + child.exitCode);
    try {
      return await getHealth(port);
    } catch (err) {
      await new Promise((r) => setTimeout(r, 150));
    }
  }
  throw new Error('no answer from /health on port ' + port);
}

async function runApp(startFile, overrides, expectPort) {
  const env = { ...process.env };
  for (const key of ['APP_NAME', 'PORT', 'GREETING', 'APP_PORT']) delete env[key];
  Object.assign(env, overrides);
  const child = spawn(process.execPath, [startFile], { cwd: root, env, stdio: ['ignore', 'pipe', 'pipe'] });
  let stderr = '';
  child.stderr.on('data', (d) => (stderr += d));
  try {
    return await waitForHealth(expectPort, child);
  } catch (err) {
    fail(err.message + (stderr ? ' | stderr: ' + stderr.trim().split(/\r?\n/)[0] : ''));
  } finally {
    child.kill();
  }
}

async function main() {
  const example = parseEnv(fs.readFileSync(path.join(root, '.env.example'), 'utf8'));
  const envFile = path.join(root, '.env');
  if (!fs.existsSync(envFile)) fail('.env does not exist (copy .env.example)');
  const local = parseEnv(fs.readFileSync(envFile, 'utf8'));
  for (const key of Object.keys(example)) {
    if (!local[key]) fail('.env is missing a value for ' + key);
  }

  const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
  const match = /^node\s+(\S+)$/.exec((pkg.scripts && pkg.scripts.start) || '');
  if (!match) fail('scripts.start must have the form "node <file>"');
  if (!fs.existsSync(path.join(root, match[1]))) fail('scripts.start points to a missing file: ' + match[1]);

  // 1. Settings come from .env.
  const health = await runApp(match[1], {}, Number(local.PORT));
  if (health.status !== 'ok') fail('unexpected status ' + JSON.stringify(health));
  if (health.app !== local.APP_NAME) fail('app name should come from .env, got ' + health.app);
  if (health.port !== Number(local.PORT)) fail('port should come from .env, got ' + health.port);

  // 2. Settings in the process environment win over .env.
  const port = await freePort();
  const over = await runApp(match[1], { PORT: String(port), APP_NAME: 'override-check' }, port);
  if (over.app !== 'override-check') fail('process environment must win over .env for APP_NAME');
  if (over.port !== port) fail('process environment must win over .env for PORT');

  console.log('OK: pingboard starts from .env and answers /health on loopback');
}

main();
