'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { InputCell, ComputeCell } = require('./reactive-cells');

function recorder() {
  const calls = [];
  const fn = (v) => calls.push(v);
  fn.calls = calls;
  return fn;
}

test('input cell holds a value and can be set', () => {
  const a = new InputCell(1);
  assert.equal(a.value, 1);
  a.setValue(2);
  assert.equal(a.value, 2);
});

test('compute cell has an initial value and follows its inputs', () => {
  const a = new InputCell(1);
  const b = new ComputeCell([a], (x) => x + 1);
  assert.equal(b.value, 2);
  a.setValue(10);
  assert.equal(b.value, 11);
});

test('compute cell with several inputs, some of them compute cells', () => {
  const a = new InputCell(2);
  const b = new InputCell(3);
  const sum = new ComputeCell([a, b], (x, y) => x + y);
  const scaled = new ComputeCell([sum, a], (s, x) => s * x);
  assert.equal(scaled.value, 10);
  a.setValue(3);
  assert.equal(sum.value, 6);
  assert.equal(scaled.value, 18);
  b.setValue(0);
  assert.equal(scaled.value, 9);
});

test('callback receives the new value, once per setValue', () => {
  const a = new InputCell(1);
  const b = new ComputeCell([a], (x) => x * 2);
  const cb = recorder();
  b.addCallback(cb);
  a.setValue(2);
  a.setValue(5);
  assert.deepEqual(cb.calls, [4, 10]);
});

test('input cells can have callbacks too', () => {
  const a = new InputCell(1);
  const cb = recorder();
  a.addCallback(cb);
  a.setValue(2);
  assert.deepEqual(cb.calls, [2]);
});

test('setting an input to its current value runs nothing', () => {
  const a = new InputCell(1);
  let computed = 0;
  const b = new ComputeCell([a], (x) => { computed += 1; return x; });
  const cb = recorder();
  a.addCallback(cb);
  b.addCallback(cb);
  a.setValue(1);
  assert.equal(computed, 1);
  assert.deepEqual(cb.calls, []);
});

test('NaN is considered equal to NaN', () => {
  const a = new InputCell(NaN);
  const cb = recorder();
  a.addCallback(cb);
  a.setValue(NaN);
  assert.deepEqual(cb.calls, []);
});

test('callback does not fire when a recomputed value stays the same', () => {
  const a = new InputCell(1);
  const big = new ComputeCell([a], (x) => x > 3);
  const cb = recorder();
  big.addCallback(cb);
  a.setValue(2);
  a.setValue(3);
  assert.deepEqual(cb.calls, []);
  a.setValue(4);
  a.setValue(5);
  a.setValue(0);
  assert.deepEqual(cb.calls, [true, false]);
});

test('a compute cell is not recomputed when none of its inputs changed value', () => {
  const x = new InputCell(5);
  const clamped = new ComputeCell([x], (v) => Math.min(v, 10));
  let downstream = 0;
  const z = new ComputeCell([clamped], (v) => { downstream += 1; return v * 2; });
  x.setValue(11);
  assert.equal(downstream, 2);
  x.setValue(12);
  x.setValue(99);
  assert.equal(downstream, 2);
  assert.equal(z.value, 20);
});

test('only dependents of the changed input are recomputed', () => {
  const a = new InputCell(1);
  const b = new InputCell(1);
  let ca = 0;
  let cb = 0;
  new ComputeCell([a], (x) => { ca += 1; return x; });
  new ComputeCell([b], (x) => { cb += 1; return x; });
  a.setValue(2);
  assert.deepEqual([ca, cb], [2, 1]);
});

test('diamond: the join cell is computed once and its callback fires once with the final value', () => {
  const a = new InputCell(1);
  const left = new ComputeCell([a], (x) => x + 1);
  const right = new ComputeCell([a], (x) => x * 10);
  let joins = 0;
  const join = new ComputeCell([left, right], (l, r) => { joins += 1; return `${l}/${r}`; });
  const cb = recorder();
  join.addCallback(cb);
  a.setValue(2);
  assert.equal(joins, 2);
  assert.deepEqual(cb.calls, ['3/20']);
  a.setValue(3);
  assert.equal(joins, 3);
  assert.deepEqual(cb.calls, ['3/20', '4/30']);
});

test('diamond whose join keeps its value fires nothing', () => {
  const a = new InputCell(1);
  const up = new ComputeCell([a], (x) => x + 1);
  const down = new ComputeCell([a], (x) => x - 1);
  const gap = new ComputeCell([up, down], (u, d) => u - d);
  const cb = recorder();
  gap.addCallback(cb);
  a.setValue(5);
  a.setValue(6);
  assert.equal(gap.value, 2);
  assert.deepEqual(cb.calls, []);
});

test('a deep chain with a shortcut still computes each cell once per round', () => {
  const a = new InputCell(1);
  const counts = { b: 0, c: 0, d: 0 };
  const b = new ComputeCell([a], (x) => { counts.b += 1; return x + 1; });
  const c = new ComputeCell([b], (x) => { counts.c += 1; return x + 1; });
  const d = new ComputeCell([a, b, c], (x, y, z) => { counts.d += 1; return x + y + z; });
  a.setValue(2);
  assert.equal(d.value, 2 + 3 + 4);
  assert.deepEqual(counts, { b: 2, c: 2, d: 2 });
});

test('callbacks see the final values of the whole round', () => {
  const a = new InputCell(1);
  const b = new ComputeCell([a], (x) => x + 1);
  const c = new ComputeCell([a], (x) => x + 100);
  const seen = [];
  b.addCallback(() => seen.push([b.value, c.value]));
  c.addCallback(() => seen.push([b.value, c.value]));
  a.setValue(2);
  assert.deepEqual(seen, [[3, 102], [3, 102]]);
});

test('callbacks run in cell creation order, then in registration order', () => {
  const a = new InputCell(1);
  const b = new ComputeCell([a], (x) => x + 1);
  const c = new ComputeCell([b], (x) => x + 1);
  const order = [];
  c.addCallback(() => order.push('c1'));
  b.addCallback(() => order.push('b1'));
  a.addCallback(() => order.push('a1'));
  c.addCallback(() => order.push('c2'));
  b.addCallback(() => order.push('b2'));
  a.setValue(2);
  assert.deepEqual(order, ['a1', 'b1', 'b2', 'c1', 'c2']);
});

test('removed callbacks are not called any more', () => {
  const a = new InputCell(1);
  const b = new ComputeCell([a], (x) => x);
  const keep = recorder();
  const drop = recorder();
  b.addCallback(keep);
  b.addCallback(drop);
  a.setValue(2);
  b.removeCallback(drop);
  a.setValue(3);
  assert.deepEqual(keep.calls, [2, 3]);
  assert.deepEqual(drop.calls, [2]);
});

test('removing a callback that was never added is harmless', () => {
  const a = new InputCell(1);
  a.removeCallback(() => {});
  a.setValue(2);
  assert.equal(a.value, 2);
});

test('adding the same function twice still calls it once per round', () => {
  const a = new InputCell(1);
  const cb = recorder();
  a.addCallback(cb);
  a.addCallback(cb);
  a.setValue(2);
  assert.deepEqual(cb.calls, [2]);
  a.removeCallback(cb);
  a.setValue(3);
  assert.deepEqual(cb.calls, [2]);
});

test('a callback removed by an earlier callback in the same round is skipped', () => {
  const a = new InputCell(1);
  const b = new ComputeCell([a], (x) => x + 1);
  const c = new ComputeCell([b], (x) => x + 1);
  const late = recorder();
  b.addCallback(() => c.removeCallback(late));
  c.addCallback(late);
  a.setValue(2);
  assert.deepEqual(late.calls, []);
});

test('the same callback can watch several cells', () => {
  const a = new InputCell(1);
  const b = new ComputeCell([a], (x) => x * 2);
  const cb = recorder();
  a.addCallback(cb);
  b.addCallback(cb);
  a.setValue(5);
  assert.deepEqual(cb.calls, [5, 10]);
});

test('setValue inside a callback throws and the engine keeps working', () => {
  const a = new InputCell(1);
  const other = new InputCell(0);
  const errors = [];
  a.addCallback(() => {
    try {
      other.setValue(1);
    } catch (e) {
      errors.push(e);
    }
  });
  a.setValue(2);
  assert.equal(errors.length, 1);
  assert.ok(errors[0] instanceof Error);
  assert.equal(other.value, 0);
  other.setValue(7);
  assert.equal(other.value, 7);
  a.setValue(3);
  assert.equal(errors.length, 2);
});
