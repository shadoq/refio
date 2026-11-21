"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { start } = require("./fakeServer");
const { ApiClient, ApiError } = require("../client");

async function withServer(handler, fn) {
  const server = await start(handler);
  const delays = [];
  const client = new ApiClient({
    baseUrl: server.url,
    token: "tok-123",
    sleep: async (ms) => {
      delays.push(ms);
    },
  });
  try {
    await fn({ client, server, delays });
  } finally {
    await server.close();
  }
}

const ok = (body) => ({ status: 200, body });

test("listAll follows opaque cursors, encodes them and sends the token", async () => {
  const next = { "": "a+b/c=", "a+b/c=": "x y&z", "x y&z": null };
  const pages = { "": [1, 2], "a+b/c=": [3, 4], "x y&z": [5] };
  await withServer(
    (req) => {
      const cursor = req.query.get("cursor") || "";
      if (!(cursor in next)) return { status: 400, body: { error: "bad cursor" } };
      return ok({ items: pages[cursor], next_cursor: next[cursor] });
    },
    async ({ client, server }) => {
      const items = await client.listAll({ pageSize: 2 });
      assert.deepEqual(items, [1, 2, 3, 4, 5]);
      assert.equal(server.requests.length, 3);
      for (const r of server.requests) {
        assert.equal(r.headers.authorization, "Bearer tok-123");
        assert.equal(r.query.get("limit"), "2");
      }
      assert.equal(server.requests[0].query.has("cursor"), false);
    }
  );
});

test("listAll uses a page size of 50 by default", async () => {
  await withServer(
    () => ok({ items: [], next_cursor: null }),
    async ({ client, server }) => {
      assert.deepEqual(await client.listAll(), []);
      assert.equal(server.requests[0].query.get("limit"), "50");
    }
  );
});

test("429 with Retry-After waits exactly that many seconds", async () => {
  await withServer(
    (req, n) => (n === 1 ? { status: 429, headers: { "retry-after": "2" } } : ok({ items: [7], next_cursor: null })),
    async ({ client, server, delays }) => {
      assert.deepEqual(await client.listAll(), [7]);
      assert.deepEqual(delays, [2000]);
      assert.equal(server.requests.length, 2);
    }
  );
});

test("429 without Retry-After backs off 1s, 2s, 4s", async () => {
  await withServer(
    (req, n) => (n <= 3 ? { status: 429 } : ok({ id: "i1" })),
    async ({ client, delays }) => {
      assert.deepEqual(await client.getItem("i1"), { id: "i1" });
      assert.deepEqual(delays, [1000, 2000, 4000]);
    }
  );
});

test("429 forever gives up after five attempts", async () => {
  await withServer(
    () => ({ status: 429, headers: { "retry-after": "1" }, body: { error: "slow down" } }),
    async ({ client, server, delays }) => {
      await assert.rejects(client.getItem("i1"), (err) => err instanceof ApiError && err.status === 429 && err.message === "slow down");
      assert.equal(server.requests.length, 5);
      assert.deepEqual(delays, [1000, 1000, 1000, 1000]);
    }
  );
});

test("GET retries 5xx twice with 100ms and 200ms delays", async () => {
  await withServer(
    (req, n) => (n === 1 ? { status: 500 } : n === 2 ? { status: 502 } : ok({ id: "i2" })),
    async ({ client, server, delays }) => {
      assert.deepEqual(await client.getItem("i2"), { id: "i2" });
      assert.deepEqual(delays, [100, 200]);
      assert.equal(server.requests.length, 3);
    }
  );
});

test("GET gives up after three attempts on a persistent 5xx", async () => {
  await withServer(
    () => ({ status: 503, body: { error: "down" } }),
    async ({ client, server }) => {
      await assert.rejects(client.listAll(), (err) => err instanceof ApiError && err.status === 503 && err.message === "down");
      assert.equal(server.requests.length, 3);
    }
  );
});

test("POST is not retried on 5xx", async () => {
  await withServer(
    () => ({ status: 500, body: { error: "boom" } }),
    async ({ client, server, delays }) => {
      await assert.rejects(client.createItem({ name: "x" }), (err) => err instanceof ApiError && err.status === 500);
      assert.equal(server.requests.length, 1);
      assert.deepEqual(delays, []);
    }
  );
});

test("POST is retried on 429 and sends its JSON body each time", async () => {
  await withServer(
    (req, n) => (n === 1 ? { status: 429, headers: { "retry-after": "1" } } : { status: 201, body: { id: "new", name: "x" } }),
    async ({ client, server, delays }) => {
      assert.deepEqual(await client.createItem({ name: "x" }), { id: "new", name: "x" });
      assert.deepEqual(delays, [1000]);
      assert.deepEqual(server.requests.map((r) => r.body), [{ name: "x" }, { name: "x" }]);
      assert.match(server.requests[0].headers["content-type"], /application\/json/);
    }
  );
});

test("404 gives null for getItem and other 4xx are final", async () => {
  await withServer(
    (req) => (req.path === "/v1/items/missing" ? { status: 404, body: { error: "nope" } } : { status: 400, body: { error: "bad cursor" } }),
    async ({ client, server, delays }) => {
      assert.equal(await client.getItem("missing"), null);
      await assert.rejects(client.listAll(), (err) => err instanceof ApiError && err.status === 400 && err.message === "bad cursor");
      assert.equal(server.requests.length, 2);
      assert.deepEqual(delays, []);
    }
  );
});

test("an error without a body falls back to the status", async () => {
  await withServer(
    () => ({ status: 403 }),
    async ({ client }) => {
      await assert.rejects(client.getItem("a"), (err) => err.status === 403 && err.message === "HTTP 403");
    }
  );
});
