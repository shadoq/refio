'use strict';
// Runs one large instance in its own process so a hopelessly slow solution can be killed from outside.
const { shortestPath } = require('../src/paths');

function lcg(seed) {
  let state = seed >>> 0;
  return () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    return state / 4294967296;
  };
}

function chain() {
  // 0 -> 1 -> ... -> 59999 with cost 1, plus toll shortcuts i -> i + 1000 costing 500.
  const n = 60000;
  const edges = [];
  for (let i = 0; i + 1 < n; i++) edges.push([i, i + 1, 1, 0]);
  for (let i = 0; i + 1000 < n; i++) edges.push([i, i + 1000, 500, 1]);
  return { n, edges, src: 0, dst: n - 1 };
}

function random() {
  const n = 40000;
  const rand = lcg(987654321);
  const edges = [];
  for (let i = 0; i < 160000; i++) {
    const u = Math.floor(rand() * n);
    const v = Math.floor(rand() * n);
    const w = Math.floor(rand() * 100);
    const toll = rand() < 0.35 ? 1 : 0;
    edges.push([u, v, toll ? Math.floor(w / 4) : w, toll]);
  }
  return { n, edges, src: 0, dst: n - 1 };
}

const which = process.argv[2];
const ks = process.argv.slice(3).map(Number);
const g = which === 'chain' ? chain() : random();
const started = process.hrtime.bigint();
const answers = ks.map((k) => shortestPath(g.n, g.edges, g.src, g.dst, k));
const elapsedMs = Number(process.hrtime.bigint() - started) / 1e6;
console.log(JSON.stringify({ answers, elapsedMs }));
