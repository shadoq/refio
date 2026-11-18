import { test } from "node:test";
import assert from "node:assert/strict";
import { buildOrder } from "../src/build-order.ts";

test("dependencies come before dependents", () => {
  assert.deepEqual(buildOrder({ app: ["lib", "util"], lib: ["util"], util: [] }), ["util", "lib", "app"]);
});

test("an empty workspace builds nothing", () => {
  assert.deepEqual(buildOrder({}), []);
});

test("modules only mentioned as dependencies are included", () => {
  assert.deepEqual(buildOrder({ app: ["zlib", "core"] }), ["core", "zlib", "app"]);
});

test("independent modules are ordered alphabetically", () => {
  assert.deepEqual(buildOrder({ c: [], b: [], a: [] }), ["a", "b", "c"]);
});

test("the smallest ready module is chosen at every step", () => {
  assert.deepEqual(buildOrder({ c: ["a"], b: [], a: [] }), ["a", "b", "c"]);
  assert.deepEqual(buildOrder({ a: ["z"], z: [], b: [] }), ["b", "z", "a"]);
});

test("a late dependency does not pull its dependents ahead of smaller ready modules", () => {
  const deps = { a: ["d"], b: [], c: [], d: ["c"] };
  assert.deepEqual(buildOrder(deps), ["b", "c", "d", "a"]);
});

test("diamond shape", () => {
  const deps = { top: ["left", "right"], left: ["base"], right: ["base"], base: [] };
  assert.deepEqual(buildOrder(deps), ["base", "left", "right", "top"]);
});

test("duplicate dependency entries count once", () => {
  assert.deepEqual(buildOrder({ b: ["a", "a", "a"], a: [] }), ["a", "b"]);
});

test("upper case sorts before lower case", () => {
  assert.deepEqual(buildOrder({ b: [], B: [], a: [] }), ["B", "a", "b"]);
});

test("does not modify the input", () => {
  const deps = { b: ["a"] };
  buildOrder(deps);
  assert.deepEqual(deps, { b: ["a"] });
});

test("a simple cycle is reported from its smallest module", () => {
  assert.throws(() => buildOrder({ b: ["a"], a: ["b"] }), { message: "Dependency cycle: a -> b -> a" });
});

test("a three module cycle starts at the smallest module even if found elsewhere", () => {
  assert.throws(() => buildOrder({ z: ["c"], c: ["b"], b: ["a"], a: ["c"] }), {
    message: "Dependency cycle: a -> c -> b -> a",
  });
});

test("self dependency is a cycle of length one", () => {
  assert.throws(() => buildOrder({ ok: [], x: ["x"] }), { message: "Dependency cycle: x -> x" });
});

test("modules outside the cycle are not in the message", () => {
  const deps = { app: ["m"], m: ["n"], n: ["o"], o: ["m"], tool: [] };
  assert.throws(() => buildOrder(deps), { message: "Dependency cycle: m -> n -> o -> m" });
});

test("the smallest module on any cycle wins, not the first one found", () => {
  const deps = { z: ["y"], y: ["z"], b: ["c"], c: ["b"], a: ["z"] };
  assert.throws(() => buildOrder(deps), { message: "Dependency cycle: b -> c -> b" });
});

test("the shortest cycle through the smallest module is reported", () => {
  const deps = { a: ["b", "d"], b: ["c"], c: ["a"], d: ["a"] };
  assert.throws(() => buildOrder(deps), { message: "Dependency cycle: a -> d -> a" });
});

test("equal length cycles are compared module by module", () => {
  const deps = { a: ["c", "b"], b: ["a"], c: ["a"] };
  assert.throws(() => buildOrder(deps), { message: "Dependency cycle: a -> b -> a" });
});

test("a longer detour does not beat a shorter cycle through the same node", () => {
  const deps = { a: ["b", "e"], b: ["c"], c: ["d"], d: ["a"], e: ["a"] };
  assert.throws(() => buildOrder(deps), { message: "Dependency cycle: a -> e -> a" });
});

test("lexicographic choice inside shortest cycles", () => {
  const deps = { a: ["c", "b"], b: ["d"], c: ["d"], d: ["a"] };
  assert.throws(() => buildOrder(deps), { message: "Dependency cycle: a -> b -> d -> a" });
});

test("a cycle with a dependency that is only mentioned, not declared", () => {
  const deps = { a: ["b"], b: ["a", "ghost"] };
  assert.throws(() => buildOrder(deps), { message: "Dependency cycle: a -> b -> a" });
});
