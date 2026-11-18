"use strict";

const http = require("node:http");

const SKU_RE = /^[A-Z]{3}-[0-9]{3}$/;

function createServer() {
  const items = new Map();

  const send = (res, status, body, headers = {}) => {
    const payload = body === undefined ? "" : JSON.stringify(body);
    res.writeHead(status, {
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...headers,
    });
    res.end(payload);
  };
  const fail = (res, status, error, headers) => send(res, status, { error }, headers);
  const etag = (item) => `"v${item.version}"`;
  const isInt = (v) => typeof v === "number" && Number.isInteger(v);

  function readBody(req) {
    return new Promise((resolve) => {
      const chunks = [];
      req.on("data", (c) => chunks.push(c));
      req.on("end", () => {
        try {
          const v = JSON.parse(Buffer.concat(chunks).toString("utf8"));
          resolve(v !== null && typeof v === "object" && !Array.isArray(v) ? v : undefined);
        } catch {
          resolve(undefined);
        }
      });
    });
  }

  function parseIntParam(raw) {
    return /^[0-9]+$/.test(raw) ? Number(raw) : null;
  }

  async function handle(req, res) {
    const url = new URL(req.url, "http://localhost");
    const method = req.method;
    const path = url.pathname;

    if (path === "/items") {
      if (method === "POST") {
        const body = await readBody(req);
        if (body === undefined) return fail(res, 400, "invalid_json");
        if (typeof body.sku !== "string" || !SKU_RE.test(body.sku) || !isInt(body.qty) || body.qty < 0) {
          return fail(res, 400, "invalid_item");
        }
        if (items.has(body.sku)) return fail(res, 409, "duplicate");
        const item = { sku: body.sku, qty: body.qty, version: 1 };
        items.set(item.sku, item);
        return send(res, 201, item, { Location: `/items/${item.sku}` });
      }
      if (method === "GET") {
        const q = url.searchParams;
        let minQty = 0;
        let limit = 10;
        if (q.has("min_qty")) {
          minQty = parseIntParam(q.get("min_qty"));
          if (minQty === null) return fail(res, 400, "invalid_query");
        }
        if (q.has("limit")) {
          limit = parseIntParam(q.get("limit"));
          if (limit === null || limit < 1 || limit > 50) return fail(res, 400, "invalid_query");
        }
        const cursor = q.get("cursor");
        const matching = [...items.values()]
          .filter((i) => i.qty >= minQty && (cursor === null || i.sku > cursor))
          .sort((a, b) => (a.sku < b.sku ? -1 : a.sku > b.sku ? 1 : 0));
        const page = matching.slice(0, limit);
        const next = matching.length > limit ? page[page.length - 1].sku : null;
        return send(res, 200, { items: page, next_cursor: next });
      }
      return fail(res, 405, "method_not_allowed", { Allow: "GET, POST" });
    }

    const m = /^\/items\/([^/]+)$/.exec(path);
    if (m) {
      const sku = decodeURIComponent(m[1]);
      if (!["GET", "PATCH", "DELETE"].includes(method)) {
        return fail(res, 405, "method_not_allowed", { Allow: "GET, PATCH, DELETE" });
      }
      const item = items.get(sku);
      if (method === "GET") {
        if (!item) return fail(res, 404, "not_found");
        return send(res, 200, item, { ETag: etag(item) });
      }
      if (method === "DELETE") {
        if (!item) return fail(res, 404, "not_found");
        items.delete(sku);
        return send(res, 204);
      }
      if (!item) return fail(res, 404, "not_found");
      const body = await readBody(req);
      if (body === undefined) return fail(res, 400, "invalid_json");
      if (!isInt(body.delta)) return fail(res, 400, "invalid_delta");
      if (item.qty + body.delta < 0) return fail(res, 422, "insufficient_stock");
      item.qty += body.delta;
      item.version += 1;
      return send(res, 200, item, { ETag: etag(item) });
    }
    return fail(res, 404, "not_found");
  }

  return http.createServer((req, res) => {
    handle(req, res).catch(() => fail(res, 500, "internal_error"));
  });
}

module.exports = { createServer };
