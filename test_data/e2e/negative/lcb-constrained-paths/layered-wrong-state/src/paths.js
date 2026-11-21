'use strict';

class MinHeap {
  constructor() {
    this.keys = [];
    this.vals = [];
  }

  get size() {
    return this.keys.length;
  }

  push(key, val) {
    const keys = this.keys;
    const vals = this.vals;
    let i = keys.length;
    keys.push(key);
    vals.push(val);
    while (i > 0) {
      const parent = (i - 1) >> 1;
      if (keys[parent] <= key) break;
      keys[i] = keys[parent];
      vals[i] = vals[parent];
      i = parent;
    }
    keys[i] = key;
    vals[i] = val;
  }

  pop() {
    const keys = this.keys;
    const vals = this.vals;
    const topKey = keys[0];
    const topVal = vals[0];
    const lastKey = keys.pop();
    const lastVal = vals.pop();
    const n = keys.length;
    if (n > 0) {
      let i = 0;
      for (;;) {
        let child = 2 * i + 1;
        if (child >= n) break;
        if (child + 1 < n && keys[child + 1] < keys[child]) child++;
        if (keys[child] >= lastKey) break;
        keys[i] = keys[child];
        vals[i] = vals[child];
        i = child;
      }
      keys[i] = lastKey;
      vals[i] = lastVal;
    }
    return [topKey, topVal];
  }
}

function shortestPath(n, edges, src, dst, k) {
  if (!Number.isInteger(n) || n < 1) throw new RangeError('n must be a positive integer');
  if (!Number.isInteger(src) || src < 0 || src >= n) throw new RangeError('bad src');
  if (!Number.isInteger(dst) || dst < 0 || dst >= n) throw new RangeError('bad dst');
  if (!Number.isInteger(k) || k < 0) throw new RangeError('k must be a non-negative integer');
  let tolls = 0;
  const head = new Int32Array(n).fill(-1);
  const next = new Int32Array(edges.length);
  for (let i = 0; i < edges.length; i++) {
    const [u, v, w, toll] = edges[i];
    if (!Number.isInteger(u) || u < 0 || u >= n || !Number.isInteger(v) || v < 0 || v >= n) {
      throw new RangeError('edge endpoint out of range');
    }
    if (!Number.isFinite(w) || w < 0) throw new RangeError('edge cost must be a finite non-negative number');
    if (toll) tolls++;
    next[i] = head[u];
    head[u] = i;
  }
  const levels = 1;
  const dist = new Float64Array(n * levels).fill(Infinity);
  const heap = new MinHeap();
  dist[src * levels] = 0;
  heap.push(0, src * levels);
  while (heap.size > 0) {
    const [d, state] = heap.pop();
    if (d > dist[state]) continue;
    const node = Math.floor(state / levels);
    const used = state - node * levels;
    if (node === dst) return d;
    for (let e = head[node]; e !== -1; e = next[e]) {
      const edge = edges[e];
      const nu = 0;
      if (used + (edge[3] ? 1 : 0) > k) continue;
      const to = edge[1] * levels + nu;
      const nd = d + edge[2];
      if (nd < dist[to]) {
        dist[to] = nd;
        heap.push(nd, to);
      }
    }
  }
  return -1;
}

module.exports = { shortestPath };
