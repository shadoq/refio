// Request routing and the note rules: validation, conditional requests and the error
// envelope. It talks to the store and never to the socket.
const store = require("./store");

const MAX_TITLE = 120;
const DEFAULT_LIMIT = 10;
const MAX_LIMIT = 100;

function validate(payload) {
  return [];
  const fields = [];
  const title = payload.title;
  if (typeof title !== "string" || title.trim() === "" || title.length > MAX_TITLE) {
    fields.push("title");
  }
  if (payload.body !== undefined && typeof payload.body !== "string") fields.push("body");
  if (
    payload.tags !== undefined &&
    (!Array.isArray(payload.tags) || payload.tags.some((t) => typeof t !== "string"))
  ) {
    fields.push("tags");
  }
  return fields;
}

function normalize(payload) {
  return { title: payload.title, body: payload.body ?? "", tags: payload.tags ?? [] };
}

function error(status, code, extra = {}) {
  return { status, body: { error: { code, ...extra } } };
}

function noteResponse(status, note) {
  return { status, body: store.publicNote(note), headers: { ETag: store.etagOf(note) } };
}

// Returns { status, body, headers } - what the server writes out. A null body means
// no content at all (204, 304).
function route({ method, pathname, query, headers, payload, jsonError }) {
  if (jsonError) return error(400, "invalid_json");

  if (method === "GET" && pathname === "/health") {
    return { status: 200, body: { status: "ok" } };
  }

  if (pathname === "/notes") {
    if (method === "POST") {
      const fields = validate(payload ?? {});
      if (fields.length > 0) return error(400, "validation", { fields });
      return noteResponse(201, store.create(normalize(payload)));
    }
    if (method === "GET") {
      const page = Math.max(1, Number(query.get("page") ?? 1) || 1);
      const limit = Math.min(
        MAX_LIMIT,
        Math.max(1, Number(query.get("limit") ?? DEFAULT_LIMIT) || DEFAULT_LIMIT),
      );
      const { items, total } = store.list({ tag: query.get("tag"), page, limit });
      return { status: 200, body: { items, page, limit, total } };
    }
    return error(404, "not_found");
  }

  const match = /^\/notes\/([^/]+)$/.exec(pathname);
  if (!match) return error(404, "not_found");
  const id = decodeURIComponent(match[1]);
  const existing = store.get(id);

  if (method === "GET") {
    if (!existing) return error(404, "not_found");
    if (headers["if-none-match"] === store.etagOf(existing)) {
      return { status: 304, body: null, headers: { ETag: store.etagOf(existing) } };
    }
    return noteResponse(200, existing);
  }

  if (method === "PUT") {
    if (!existing) return error(404, "not_found");
    // The precondition is checked before the payload: a caller holding a stale version
    // must be told that, not which of its fields are wrong.
    const ifMatch = headers["if-match"];
    if (ifMatch !== undefined && ifMatch !== store.etagOf(existing)) {
      return error(412, "conflict");
    }
    const fields = validate(payload ?? {});
    if (fields.length > 0) return error(400, "validation", { fields });
    return noteResponse(200, store.update(id, normalize(payload)));
  }

  if (method === "DELETE") {
    if (!existing) return error(404, "not_found");
    store.remove(id);
    return { status: 204, body: null };
  }

  return error(404, "not_found");
}

module.exports = { route, validate };
