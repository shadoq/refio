import { test } from "node:test";
import assert from "node:assert/strict";
import { IntervalIndex } from "../src/interval-index.ts";

function ids(list: { id: string }[]): string[] {
  return list.map((i) => i.id);
}

test("point query uses closed bounds on both ends", () => {
  const idx = new IntervalIndex();
  idx.add("a", 5, 10);
  assert.deepEqual(ids(idx.queryPoint(5)), ["a"]);
  assert.deepEqual(ids(idx.queryPoint(10)), ["a"]);
  assert.deepEqual(ids(idx.queryPoint(7.5)), ["a"]);
  assert.deepEqual(ids(idx.queryPoint(4.999)), []);
  assert.deepEqual(ids(idx.queryPoint(10.001)), []);
});

test("zero-length interval covers exactly one point", () => {
  const idx = new IntervalIndex();
  idx.add("dot", 3, 3);
  assert.deepEqual(ids(idx.queryPoint(3)), ["dot"]);
  assert.deepEqual(ids(idx.queryPoint(2)), []);
  assert.deepEqual(ids(idx.queryRange(3, 3)), ["dot"]);
  assert.deepEqual(ids(idx.queryRange(0, 3)), ["dot"]);
  assert.deepEqual(ids(idx.queryRange(3, 9)), ["dot"]);
  assert.deepEqual(ids(idx.queryRange(4, 9)), []);
});

test("range query counts touching intervals as overlapping", () => {
  const idx = new IntervalIndex();
  idx.add("left", 0, 5);
  idx.add("right", 10, 20);
  idx.add("far", 30, 40);
  assert.deepEqual(ids(idx.queryRange(5, 10)), ["left", "right"]);
  assert.deepEqual(ids(idx.queryRange(6, 9)), []);
  assert.deepEqual(ids(idx.queryRange(-100, 100)), ["left", "right", "far"]);
});

test("range query finds an interval that fully contains the range", () => {
  const idx = new IntervalIndex();
  idx.add("wide", 0, 100);
  idx.add("narrow", 40, 41);
  assert.deepEqual(ids(idx.queryRange(50, 60)), ["wide"]);
  assert.deepEqual(ids(idx.queryRange(41, 41)), ["wide", "narrow"]);
});

test("queryRange(x, x) equals queryPoint(x)", () => {
  const idx = new IntervalIndex();
  idx.add("a", 1, 4);
  idx.add("b", 4, 4);
  idx.add("c", 4, 9);
  idx.add("d", 5, 9);
  assert.deepEqual(idx.queryRange(4, 4), idx.queryPoint(4));
  assert.deepEqual(ids(idx.queryPoint(4)), ["a", "b", "c"]);
});

test("results are sorted by start then id, not by insertion order", () => {
  const idx = new IntervalIndex();
  idx.add("m", 5, 9);
  idx.add("z", 1, 9);
  idx.add("b", 5, 6);
  idx.add("a", 5, 20);
  idx.add("c", 0, 9);
  assert.deepEqual(ids(idx.queryPoint(5)), ["c", "z", "a", "b", "m"]);
});

test("ids compare by code unit order, not locale order", () => {
  const idx = new IntervalIndex();
  idx.add("a", 1, 2);
  idx.add("B", 1, 2);
  idx.add("_", 1, 2);
  idx.add("Z", 1, 2);
  assert.deepEqual(ids(idx.queryPoint(1)), ["B", "Z", "_", "a"]);
});

test("identical bounds with different ids are all kept", () => {
  const idx = new IntervalIndex();
  idx.add("two", 2, 8);
  idx.add("one", 2, 8);
  idx.add("three", 2, 8);
  assert.equal(idx.size, 3);
  assert.deepEqual(ids(idx.queryPoint(2)), ["one", "three", "two"]);
});

test("adding an existing id throws Error and changes nothing", () => {
  const idx = new IntervalIndex();
  idx.add("a", 1, 2);
  assert.throws(() => idx.add("a", 5, 6), (e: unknown) => e instanceof Error && !(e instanceof RangeError));
  assert.equal(idx.size, 1);
  assert.deepEqual(idx.queryPoint(5), []);
  assert.deepEqual(ids(idx.queryPoint(1)), ["a"]);
});

test("invalid bounds throw RangeError and change nothing", () => {
  const idx = new IntervalIndex();
  assert.throws(() => idx.add("a", 5, 4), RangeError);
  assert.throws(() => idx.add("a", NaN, 4), RangeError);
  assert.throws(() => idx.add("a", 1, Infinity), RangeError);
  assert.throws(() => idx.add("a", -Infinity, 1), RangeError);
  assert.equal(idx.size, 0);
  idx.add("a", 1, 2);
  assert.equal(idx.size, 1);
});

test("invalid range queries throw RangeError", () => {
  const idx = new IntervalIndex();
  idx.add("a", 1, 2);
  assert.throws(() => idx.queryRange(5, 4), RangeError);
  assert.throws(() => idx.queryRange(NaN, 4), RangeError);
  assert.throws(() => idx.queryRange(1, Infinity), RangeError);
});

test("remove reports whether something was removed and frees the id", () => {
  const idx = new IntervalIndex();
  idx.add("a", 1, 5);
  idx.add("b", 2, 6);
  assert.equal(idx.remove("a"), true);
  assert.equal(idx.remove("a"), false);
  assert.equal(idx.remove("nope"), false);
  assert.equal(idx.size, 1);
  assert.deepEqual(ids(idx.queryPoint(3)), ["b"]);
  idx.add("a", 10, 11);
  assert.deepEqual(idx.queryPoint(10), [{ id: "a", start: 10, end: 11 }]);
});

test("removing one of several identical intervals keeps the others", () => {
  const idx = new IntervalIndex();
  idx.add("x", 1, 3);
  idx.add("y", 1, 3);
  idx.remove("x");
  assert.deepEqual(idx.queryPoint(2), [{ id: "y", start: 1, end: 3 }]);
});

test("results are copies, so mutating them does not change the index", () => {
  const idx = new IntervalIndex();
  idx.add("a", 1, 5);
  const first = idx.queryPoint(2);
  first[0].end = 100;
  first[0].id = "hacked";
  first.length = 0;
  assert.deepEqual(idx.queryPoint(2), [{ id: "a", start: 1, end: 5 }]);
  assert.deepEqual(idx.queryPoint(50), []);
});

test("negative and fractional coordinates work", () => {
  const idx = new IntervalIndex();
  idx.add("neg", -10, -2.5);
  idx.add("mix", -3, 3);
  assert.deepEqual(ids(idx.queryPoint(-2.5)), ["neg", "mix"]);
  assert.deepEqual(ids(idx.queryPoint(0)), ["mix"]);
  assert.deepEqual(ids(idx.queryRange(-20, -11)), []);
});

test("matches a brute-force scan on a seeded random workload", () => {
  let seed = 12345;
  const rnd = (n: number) => {
    seed = (seed * 1103515245 + 12345) & 0x7fffffff;
    return seed % n;
  };
  const idx = new IntervalIndex();
  const model = new Map<string, { id: string; start: number; end: number }>();
  const brute = (pred: (i: { start: number; end: number }) => boolean) =>
    [...model.values()]
      .filter(pred)
      .sort((p, q) => p.start - q.start || (p.id < q.id ? -1 : p.id > q.id ? 1 : 0));
  for (let step = 0; step < 600; step++) {
    const action = rnd(10);
    if (action < 6) {
      const id = "i" + rnd(120);
      const start = rnd(60) - 20;
      const end = start + (rnd(4) === 0 ? 0 : rnd(25));
      if (model.has(id)) {
        assert.throws(() => idx.add(id, start, end));
      } else {
        idx.add(id, start, end);
        model.set(id, { id, start, end });
      }
    } else if (action < 8) {
      const id = "i" + rnd(120);
      assert.equal(idx.remove(id), model.delete(id));
    } else if (action < 9) {
      const x = rnd(80) - 25;
      assert.deepEqual(idx.queryPoint(x), brute((i) => i.start <= x && x <= i.end));
    } else {
      const lo = rnd(80) - 25;
      const hi = lo + rnd(15);
      assert.deepEqual(idx.queryRange(lo, hi), brute((i) => i.start <= hi && i.end >= lo));
    }
    assert.equal(idx.size, model.size);
  }
});
