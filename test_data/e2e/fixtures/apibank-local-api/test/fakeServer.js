"use strict";

const http = require("node:http");

// handler(request, attemptNumberForThisMethodAndPath) -> { status, headers?, body? }
async function start(handler) {
  const requests = [];
  const counts = new Map();
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf8");
      const url = new URL(req.url, "http://local");
      const record = {
        method: req.method,
        path: url.pathname,
        query: url.searchParams,
        headers: req.headers,
        body: raw ? JSON.parse(raw) : undefined,
      };
      requests.push(record);
      const key = req.method + " " + url.pathname;
      const n = (counts.get(key) || 0) + 1;
      counts.set(key, n);
      const reply = handler(record, n);
      res.writeHead(reply.status, { "content-type": "application/json", ...(reply.headers || {}) });
      res.end(reply.body === undefined ? "" : JSON.stringify(reply.body));
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return {
    url: "http://127.0.0.1:" + server.address().port,
    requests,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

module.exports = { start };
