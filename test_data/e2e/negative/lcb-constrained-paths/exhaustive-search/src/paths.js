'use strict';

function shortestPath(n, edges, src, dst, k) {
  if (!Number.isInteger(n) || n < 1) throw new RangeError('n must be a positive integer');
  if (!Number.isInteger(src) || src < 0 || src >= n) throw new RangeError('bad src');
  if (!Number.isInteger(dst) || dst < 0 || dst >= n) throw new RangeError('bad dst');
  if (!Number.isInteger(k) || k < 0) throw new RangeError('k must be a non-negative integer');
  for (const [u, v, w] of edges) {
    if (!Number.isInteger(u) || u < 0 || u >= n || !Number.isInteger(v) || v < 0 || v >= n) throw new RangeError('edge endpoint out of range');
    if (!Number.isFinite(w) || w < 0) throw new RangeError('edge cost must be a finite non-negative number');
  }
  const out = Array.from({ length: n }, () => []);
  for (const e of edges) out[e[0]].push(e);
  let best = Infinity;
  const seen = new Array(n).fill(false);
  function dfs(node, cost, tolls) {
    if (tolls > k || cost >= best) return;
    if (node === dst) {
      best = cost;
      return;
    }
    seen[node] = true;
    for (const [, v, w, toll] of out[node]) {
      if (!seen[v]) dfs(v, cost + w, tolls + (toll ? 1 : 0));
    }
    seen[node] = false;
  }
  dfs(src, 0, 0);
  return best === Infinity ? -1 : best;
}

module.exports = { shortestPath };
