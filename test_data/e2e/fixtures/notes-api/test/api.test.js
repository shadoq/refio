// The hidden acceptance suite for the notes API. It starts src/server.js as a child
// process on a free port, waits for /health and then drives the API over HTTP, so it
// tests the server as a user meets it and not the shape of the code behind it.
const { test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const http = require("node:http");
const path = require("node:path");

const PORT = Number(process.env.PORT || 4321);
const BASE = `http://127.0.0.1:${PORT}`;
let child;

function request(method, url, { body, headers } = {}) {
  return new Promise((resolve, reject) => {
    const payload = body === undefined ? null : JSON.stringify(body);
    const req = http.request(
      `${BASE}${url}`,
      {
        method,
        headers: {
          ...(payload ? { "content-type": "application/json", "content-length": Buffer.byteLength(payload) } : {}),
          ...headers,
        },
      },
      (res) => {
        let text = "";
        res.on("data", (chunk) => (text += chunk));
        res.on("end", () => {
          let json = null;
          if (text.length > 0) {
            try {
              json = JSON.parse(text);
            } catch {
              json = null;
            }
          }
          resolve({ status: res.statusCode, headers: res.headers, body: json, text });
        });
      },
    );
    req.on("error", reject);
    if (payload) req.write(payload);
    req.end();
  });
}

async function waitForHealth(timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    try {
      const res = await request("GET", "/health");
      if (res.status === 200) return;
    } catch {
      // not listening yet
    }
    if (Date.now() > deadline) throw new Error("server did not answer /health in time");
    await new Promise((r) => setTimeout(r, 200));
  }
}

before(async () => {
  const serverPath = path.join(__dirname, "..", "src", "server.js");
  child = spawn(process.execPath, [serverPath], {
    env: { ...process.env, PORT: String(PORT) },
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout.on("data", () => {});
  child.stderr.on("data", (d) => process.stderr.write(d));
  await waitForHealth();
});

after(() => {
  if (child) child.kill();
});

async function createNote(over = {}) {
  const res = await request("POST", "/notes", {
    body: { title: "first", body: "hello", tags: ["work"], ...over },
  });
  assert.equal(res.status, 201);
  return res;
}

test("health reports the server is up", async () => {
  const res = await request("GET", "/health");
  assert.equal(res.status, 200);
  assert.deepEqual(res.body, { status: "ok" });
});

test("creating a note returns it with an id, timestamps and an ETag", async () => {
  const res = await createNote({ title: "created" });
  assert.equal(typeof res.body.id, "string");
  assert.equal(res.body.title, "created");
  assert.equal(res.body.body, "hello");
  assert.deepEqual(res.body.tags, ["work"]);
  assert.ok(!Number.isNaN(Date.parse(res.body.createdAt)));
  assert.ok(!Number.isNaN(Date.parse(res.body.updatedAt)));
  assert.ok(res.headers.etag, "the response must carry an ETag header");
});

test("body and tags may be omitted", async () => {
  const res = await request("POST", "/notes", { body: { title: "bare" } });
  assert.equal(res.status, 201);
  assert.equal(res.body.body, "");
  assert.deepEqual(res.body.tags, []);
});

test("a missing title is rejected as a validation error", async () => {
  const res = await request("POST", "/notes", { body: { body: "no title" } });
  assert.equal(res.status, 400);
  assert.equal(res.body.error.code, "validation");
  assert.ok(res.body.error.fields.includes("title"));
});

test("a title longer than 120 characters is rejected", async () => {
  const res = await request("POST", "/notes", { body: { title: "x".repeat(121) } });
  assert.equal(res.status, 400);
  assert.equal(res.body.error.code, "validation");
  assert.ok(res.body.error.fields.includes("title"));
});

test("tags must be an array of strings", async () => {
  const res = await request("POST", "/notes", { body: { title: "bad tags", tags: "work" } });
  assert.equal(res.status, 400);
  assert.ok(res.body.error.fields.includes("tags"));
});

test("a body that is not JSON is rejected", async () => {
  const res = await new Promise((resolve, reject) => {
    const req = http.request(
      `${BASE}/notes`,
      { method: "POST", headers: { "content-type": "application/json" } },
      (r) => {
        let text = "";
        r.on("data", (c) => (text += c));
        r.on("end", () => resolve({ status: r.statusCode, body: text ? JSON.parse(text) : null }));
      },
    );
    req.on("error", reject);
    req.write("{not json");
    req.end();
  });
  assert.equal(res.status, 400);
  assert.equal(res.body.error.code, "invalid_json");
});

test("a single note can be fetched by id", async () => {
  const created = await createNote({ title: "fetch me" });
  const res = await request("GET", `/notes/${created.body.id}`);
  assert.equal(res.status, 200);
  assert.equal(res.body.title, "fetch me");
  assert.ok(res.headers.etag);
});

test("an unknown id is not found", async () => {
  const res = await request("GET", "/notes/does-not-exist");
  assert.equal(res.status, 404);
  assert.equal(res.body.error.code, "not_found");
});

test("a matching If-None-Match is answered with 304 and no body", async () => {
  const created = await createNote({ title: "cached" });
  const first = await request("GET", `/notes/${created.body.id}`);
  const res = await request("GET", `/notes/${created.body.id}`, {
    headers: { "if-none-match": first.headers.etag },
  });
  assert.equal(res.status, 304);
  assert.equal(res.text, "");
});

test("listing filters by tag and reports the total behind the page", async () => {
  const tag = `t${Date.now()}`;
  await createNote({ title: "a", tags: [tag] });
  await createNote({ title: "b", tags: [tag] });
  await createNote({ title: "c", tags: ["other"] });

  const res = await request("GET", `/notes?tag=${tag}&page=1&limit=1`);
  assert.equal(res.status, 200);
  assert.equal(res.body.items.length, 1);
  assert.equal(res.body.page, 1);
  assert.equal(res.body.limit, 1);
  assert.equal(res.body.total, 2);

  const second = await request("GET", `/notes?tag=${tag}&page=2&limit=1`);
  assert.equal(second.body.items.length, 1);
  assert.notEqual(second.body.items[0].id, res.body.items[0].id);
});

test("listing is sorted newest first", async () => {
  const tag = `s${Date.now()}`;
  const first = await createNote({ title: "older", tags: [tag] });
  await new Promise((r) => setTimeout(r, 5));
  const second = await createNote({ title: "newer", tags: [tag] });
  const res = await request("GET", `/notes?tag=${tag}`);
  assert.equal(res.body.items[0].id, second.body.id);
  assert.equal(res.body.items[1].id, first.body.id);
});

test("updating a note refreshes updatedAt and keeps createdAt", async () => {
  const created = await createNote({ title: "before" });
  await new Promise((r) => setTimeout(r, 5));
  const res = await request("PUT", `/notes/${created.body.id}`, {
    body: { title: "after", body: "changed", tags: ["x"] },
  });
  assert.equal(res.status, 200);
  assert.equal(res.body.title, "after");
  assert.equal(res.body.createdAt, created.body.createdAt);
  assert.notEqual(res.body.updatedAt, created.body.updatedAt);
  assert.ok(res.headers.etag);
});

test("an update validates the same way a create does", async () => {
  const created = await createNote({ title: "valid" });
  const res = await request("PUT", `/notes/${created.body.id}`, { body: { title: "" } });
  assert.equal(res.status, 400);
  assert.equal(res.body.error.code, "validation");
});

test("a stale If-Match is refused and changes nothing", async () => {
  const created = await createNote({ title: "guarded" });
  const res = await request("PUT", `/notes/${created.body.id}`, {
    body: { title: "sneaky" },
    headers: { "if-match": '"not-the-current-version"' },
  });
  assert.equal(res.status, 412);
  assert.equal(res.body.error.code, "conflict");

  const after = await request("GET", `/notes/${created.body.id}`);
  assert.equal(after.body.title, "guarded");
});

test("a current If-Match is accepted", async () => {
  const created = await createNote({ title: "guarded ok" });
  const current = await request("GET", `/notes/${created.body.id}`);
  const res = await request("PUT", `/notes/${created.body.id}`, {
    body: { title: "updated" },
    headers: { "if-match": current.headers.etag },
  });
  assert.equal(res.status, 200);
  assert.equal(res.body.title, "updated");
});

test("updating an unknown id is not found", async () => {
  const res = await request("PUT", "/notes/nope", { body: { title: "x" } });
  assert.equal(res.status, 404);
});

test("deleting a note answers 204 and then the note is gone", async () => {
  const created = await createNote({ title: "doomed" });
  const res = await request("DELETE", `/notes/${created.body.id}`);
  assert.equal(res.status, 204);
  assert.equal(res.text, "");

  assert.equal((await request("GET", `/notes/${created.body.id}`)).status, 404);
  assert.equal((await request("DELETE", `/notes/${created.body.id}`)).status, 404);
});

test("an unknown path is not found", async () => {
  const res = await request("GET", "/nothing-here");
  assert.equal(res.status, 404);
  assert.equal(res.body.error.code, "not_found");
});
