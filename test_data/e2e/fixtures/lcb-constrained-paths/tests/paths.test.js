'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { shortestPath } = require('../src/paths');

function lcg(seed) {
  let state = seed >>> 0;
  return () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    return state / 4294967296;
  };
}

// Exhaustive reference for tiny graphs: every simple path, counting tolls.
function brute(n, edges, src, dst, k) {
  let best = Infinity;
  const seen = new Array(n).fill(false);
  function dfs(node, cost, tolls) {
    if (tolls > k || cost >= best) return;
    if (node === dst) {
      best = cost;
      return;
    }
    seen[node] = true;
    for (const [u, v, w, toll] of edges) {
      if (u === node && !seen[v]) dfs(v, cost + w, tolls + (toll ? 1 : 0));
    }
    seen[node] = false;
  }
  dfs(src, 0, 0);
  return best === Infinity ? -1 : best;
}

test('toll road is used only when the budget allows it', () => {
  const edges = [[0, 1, 10, 0], [1, 2, 10, 0], [0, 2, 3, 1]];
  assert.equal(shortestPath(3, edges, 0, 2, 0), 20);
  assert.equal(shortestPath(3, edges, 0, 2, 1), 3);
});

test('two tolls are worth it only with k >= 2', () => {
  const edges = [[0, 1, 1, 1], [1, 2, 1, 1], [0, 2, 50, 0]];
  assert.equal(shortestPath(3, edges, 0, 2, 1), 50);
  assert.equal(shortestPath(3, edges, 0, 2, 2), 2);
});

test('roads are directed', () => {
  assert.equal(shortestPath(2, [[1, 0, 1, 0]], 0, 1, 5), -1);
  assert.equal(shortestPath(2, [[1, 0, 1, 0]], 1, 0, 0), 1);
});

test('start equals destination costs nothing, even with k = 0', () => {
  assert.equal(shortestPath(3, [[0, 1, 5, 1]], 2, 2, 0), 0);
});

test('unreachable within the toll budget gives -1', () => {
  assert.equal(shortestPath(2, [[0, 1, 1, 1]], 0, 1, 0), -1);
});

test('parallel roads, self loops and zero cost roads', () => {
  const edges = [[0, 0, 0, 1], [0, 1, 9, 0], [0, 1, 4, 0], [0, 1, 0, 1], [1, 1, 3, 0]];
  assert.equal(shortestPath(2, edges, 0, 1, 0), 4);
  assert.equal(shortestPath(2, edges, 0, 1, 1), 0);
});

test('a huge k does not allocate per k', () => {
  const edges = [[0, 1, 2, 1], [1, 2, 2, 1], [0, 2, 9, 0]];
  assert.equal(shortestPath(3, edges, 0, 2, 1000000000), 4);
});

test('toll flag may be boolean', () => {
  assert.equal(shortestPath(2, [[0, 1, 7, true]], 0, 1, 0), -1);
  assert.equal(shortestPath(2, [[0, 1, 7, false]], 0, 1, 0), 7);
});

test('invalid arguments throw RangeError', () => {
  assert.throws(() => shortestPath(2, [[0, 1, -1, 0]], 0, 1, 0), RangeError);
  assert.throws(() => shortestPath(2, [[0, 2, 1, 0]], 0, 1, 0), RangeError);
  assert.throws(() => shortestPath(2, [], 0, 5, 0), RangeError);
  assert.throws(() => shortestPath(2, [], 0, 1, -1), RangeError);
  assert.throws(() => shortestPath(2, [], 0, 1, 1.5), RangeError);
});

test('random small graphs agree with exhaustive search', () => {
  const rand = lcg(4242);
  for (let round = 0; round < 500; round++) {
    const n = 2 + Math.floor(rand() * 5);
    const m = Math.floor(rand() * 12);
    const edges = [];
    for (let i = 0; i < m; i++) {
      edges.push([Math.floor(rand() * n), Math.floor(rand() * n), Math.floor(rand() * 10), rand() < 0.5 ? 1 : 0]);
    }
    const src = Math.floor(rand() * n);
    const dst = Math.floor(rand() * n);
    const k = Math.floor(rand() * 4);
    assert.equal(shortestPath(n, edges, src, dst, k), brute(n, edges, src, dst, k), JSON.stringify({ n, edges, src, dst, k }));
  }
});

function runLarge(which, ks) {
  const result = spawnSync(process.execPath, [path.join(__dirname, 'perf_case.js'), which, ...ks.map(String)], {
    encoding: 'utf8',
    timeout: 15000,
  });
  assert.equal(result.error, undefined, 'large case did not finish within 15 s: ' + (result.error && result.error.message));
  assert.equal(result.status, 0, result.stderr);
  return JSON.parse(result.stdout);
}

test('long chain with toll shortcuts finishes quickly', () => {
  const out = runLarge('chain', [0, 3, 10]);
  assert.deepEqual(out.answers, [59999, 59999 - 3 * 500, 59999 - 10 * 500]);
  assert.ok(out.elapsedMs < 6000, 'took ' + out.elapsedMs + ' ms');
});

test('large random network finishes quickly', () => {
  const out = runLarge('random', [0, 2, 5]);
  assert.deepEqual(out.answers, EXPECTED_RANDOM);
  assert.ok(out.elapsedMs < 6000, 'took ' + out.elapsedMs + ' ms');
});

const EXPECTED_RANDOM = [498, 276, 233];
