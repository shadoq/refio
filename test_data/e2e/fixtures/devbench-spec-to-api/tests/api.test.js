"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { createServer } = require("../src/server.js");

async function start() {
  const server = createServer();
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const call = async (method, path, body, headers = {}) => {
    const init = { method, headers: { ...headers } };
    if (body !== undefined) {
      init.body = typeof body === "string" ? body : JSON.stringify(body);
      init.headers["content-type"] = "application/json";
    }
    const res = await fetch(base + path, init);
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { json = undefined; }
    return { status: res.status, headers: res.headers, json, text };
  };
  return { call, close: () => new Promise((r) => { server.closeAllConnections?.(); server.close(r); }) };
}

async function withServer(fn) {
  const s = await start();
  try { await fn(s.call); } finally { await s.close(); }
}

test("create and read an item", () => withServer(async (call) => {
  const res = await call("POST", "/items", { sku: "ABC-123", qty: 5 });
  assert.equal(res.status, 201);
  assert.deepEqual(res.json, { sku: "ABC-123", qty: 5, version: 1 });
  assert.equal(res.headers.get("location"), "/items/ABC-123");
  assert.match(res.headers.get("content-type"), /application\/json/);
  const got = await call("GET", "/items/ABC-123");
  assert.equal(got.status, 200);
  assert.equal(got.headers.get("etag"), '"v1"');
  assert.deepEqual(got.json, { sku: "ABC-123", qty: 5, version: 1 });
}));

test("create validation", () => withServer(async (call) => {
  assert.deepEqual((await call("POST", "/items", "{oops")).json, { error: "invalid_json" });
  assert.equal((await call("POST", "/items", "{oops")).status, 400);
  assert.deepEqual((await call("POST", "/items", "[1,2]")).json, { error: "invalid_json" });
  for (const bad of [
    { sku: "abc-123", qty: 1 }, { sku: "ABCD-123", qty: 1 }, { sku: "ABC-12", qty: 1 },
    { sku: "ABC-123", qty: -1 }, { sku: "ABC-123", qty: 1.5 }, { sku: "ABC-123", qty: "3" }, { qty: 1 },
  ]) {
    const res = await call("POST", "/items", bad);
    assert.equal(res.status, 400, JSON.stringify(bad));
    assert.deepEqual(res.json, { error: "invalid_item" });
  }
  assert.equal((await call("POST", "/items", { sku: "ABC-123", qty: 0 })).status, 201);
  const dup = await call("POST", "/items", { sku: "ABC-123", qty: 9 });
  assert.equal(dup.status, 409);
  assert.deepEqual(dup.json, { error: "duplicate" });
}));

test("unknown sku", () => withServer(async (call) => {
  for (const m of ["GET", "DELETE"]) {
    const res = await call(m, "/items/ZZZ-999");
    assert.equal(res.status, 404);
    if (m === "GET") assert.deepEqual(res.json, { error: "not_found" });
  }
  const p = await call("PATCH", "/items/ZZZ-999", { delta: 1 });
  assert.equal(p.status, 404);
  assert.deepEqual(p.json, { error: "not_found" });
}));

test("patch needs a matching If-Match", () => withServer(async (call) => {
  await call("POST", "/items", { sku: "ABC-123", qty: 5 });
  const missing = await call("PATCH", "/items/ABC-123", { delta: 1 });
  assert.equal(missing.status, 428);
  assert.deepEqual(missing.json, { error: "precondition_required" });
  const wrong = await call("PATCH", "/items/ABC-123", { delta: 1 }, { "if-match": '"v7"' });
  assert.equal(wrong.status, 412);
  assert.deepEqual(wrong.json, { error: "precondition_failed" });
  const unquoted = await call("PATCH", "/items/ABC-123", { delta: 1 }, { "if-match": "v1" });
  assert.equal(unquoted.status, 412);
  const ok = await call("PATCH", "/items/ABC-123", { delta: 3 }, { "if-match": '"v1"' });
  assert.equal(ok.status, 200);
  assert.deepEqual(ok.json, { sku: "ABC-123", qty: 8, version: 2 });
  assert.equal(ok.headers.get("etag"), '"v2"');
  const stale = await call("PATCH", "/items/ABC-123", { delta: 1 }, { "if-match": '"v1"' });
  assert.equal(stale.status, 412);
}));

test("patch body rules are checked after the precondition", () => withServer(async (call) => {
  await call("POST", "/items", { sku: "ABC-123", qty: 5 });
  const h = { "if-match": '"v1"' };
  const bad = await call("PATCH", "/items/ABC-123", "{nope", h);
  assert.equal(bad.status, 400);
  assert.deepEqual(bad.json, { error: "invalid_json" });
  const notInt = await call("PATCH", "/items/ABC-123", { delta: 1.5 }, h);
  assert.deepEqual(notInt.json, { error: "invalid_delta" });
  assert.equal(notInt.status, 400);
  const badBodyWrongEtag = await call("PATCH", "/items/ABC-123", { delta: "x" }, { "if-match": '"v9"' });
  assert.equal(badBodyWrongEtag.status, 412);
  const under = await call("PATCH", "/items/ABC-123", { delta: -6 }, h);
  assert.equal(under.status, 422);
  assert.deepEqual(under.json, { error: "insufficient_stock" });
  const still = await call("GET", "/items/ABC-123");
  assert.deepEqual(still.json, { sku: "ABC-123", qty: 5, version: 1 });
  const exact = await call("PATCH", "/items/ABC-123", { delta: -5 }, h);
  assert.equal(exact.status, 200);
  assert.equal(exact.json.qty, 0);
}));

test("delete", () => withServer(async (call) => {
  await call("POST", "/items", { sku: "ABC-123", qty: 5 });
  const del = await call("DELETE", "/items/ABC-123");
  assert.equal(del.status, 204);
  assert.equal(del.text, "");
  assert.equal((await call("GET", "/items/ABC-123")).status, 404);
  assert.equal((await call("POST", "/items", { sku: "ABC-123", qty: 1 })).status, 201);
}));

test("list: sorting, filter, pagination", () => withServer(async (call) => {
  for (const [sku, qty] of [["DDD-004", 0], ["AAA-001", 5], ["CCC-003", 7], ["BBB-002", 2], ["EEE-005", 9]]) {
    await call("POST", "/items", { sku, qty });
  }
  const all = await call("GET", "/items");
  assert.equal(all.status, 200);
  assert.deepEqual(all.json.items.map((i) => i.sku), ["AAA-001", "BBB-002", "CCC-003", "DDD-004", "EEE-005"]);
  assert.equal(all.json.next_cursor, null);

  const p1 = await call("GET", "/items?limit=2");
  assert.deepEqual(p1.json.items.map((i) => i.sku), ["AAA-001", "BBB-002"]);
  assert.equal(p1.json.next_cursor, "BBB-002");
  const p2 = await call("GET", "/items?limit=2&cursor=BBB-002");
  assert.deepEqual(p2.json.items.map((i) => i.sku), ["CCC-003", "DDD-004"]);
  assert.equal(p2.json.next_cursor, "DDD-004");
  const p3 = await call("GET", "/items?limit=2&cursor=DDD-004");
  assert.deepEqual(p3.json.items.map((i) => i.sku), ["EEE-005"]);
  assert.equal(p3.json.next_cursor, null);

  // the filter applies before paging: next_cursor only when more MATCHING items remain
  const f = await call("GET", "/items?min_qty=5&limit=2");
  assert.deepEqual(f.json.items.map((i) => i.sku), ["AAA-001", "CCC-003"]);
  assert.equal(f.json.next_cursor, "CCC-003");
  const f2 = await call("GET", "/items?min_qty=5&limit=2&cursor=CCC-003");
  assert.deepEqual(f2.json.items.map((i) => i.sku), ["EEE-005"]);
  assert.equal(f2.json.next_cursor, null);
  const exactFit = await call("GET", "/items?min_qty=5&limit=3");
  assert.equal(exactFit.json.items.length, 3);
  assert.equal(exactFit.json.next_cursor, null);
  const unknownCursor = await call("GET", "/items?cursor=BBB-999&limit=1");
  assert.deepEqual(unknownCursor.json.items.map((i) => i.sku), ["CCC-003"]);
}));

test("list: query validation", () => withServer(async (call) => {
  for (const q of ["limit=0", "limit=51", "limit=abc", "limit=1.5", "min_qty=-1", "min_qty=x"]) {
    const res = await call("GET", "/items?" + q);
    assert.equal(res.status, 400, q);
    assert.deepEqual(res.json, { error: "invalid_query" });
  }
  assert.equal((await call("GET", "/items?limit=50")).status, 200);
}));

test("routing errors", () => withServer(async (call) => {
  const nf = await call("GET", "/nope");
  assert.equal(nf.status, 404);
  assert.deepEqual(nf.json, { error: "not_found" });
  const m1 = await call("PUT", "/items");
  assert.equal(m1.status, 405);
  assert.deepEqual(m1.json, { error: "method_not_allowed" });
  assert.equal(m1.headers.get("allow"), "GET, POST");
  const m2 = await call("POST", "/items/ABC-123", {});
  assert.equal(m2.status, 405);
  assert.equal(m2.headers.get("allow"), "GET, PATCH, DELETE");
}));

test("servers are independent", async () => {
  const a = await start();
  const b = await start();
  try {
    assert.equal((await a.call("POST", "/items", { sku: "ABC-123", qty: 1 })).status, 201);
    assert.equal((await b.call("GET", "/items/ABC-123")).status, 404);
    assert.equal((await b.call("POST", "/items", { sku: "ABC-123", qty: 1 })).status, 201);
  } finally {
    await a.close();
    await b.close();
  }
});
