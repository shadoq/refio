"use strict";

const { parse } = require("./params");
const { exportCsv } = require("../export/csv");
const { exportJson } = require("../export/stream");

async function route(req, res, config) {
  const url = new URL(req.url, "http://localhost");
  const query = parse(url.search.slice(1));
  if (url.pathname === "/health") {
    res.setHeader("content-type", "text/plain");
    res.end("ok");
    return;
  }
  if (url.pathname === "/export.csv") {
    res.setHeader("content-type", "text/csv");
    res.end(await exportCsv(query, config));
    return;
  }
  if (url.pathname === "/export.json") {
    res.setHeader("content-type", "application/json");
    res.end(await exportJson(query, config));
    return;
  }
  res.statusCode = 404;
  res.end(JSON.stringify({ error: "unknown route" }));
}

module.exports = { route };
