"use strict";

const http = require("http");
const { loadConfig } = require("./config");
const { route } = require("./http/router");

function createServer(config) {
  return http.createServer((req, res) => {
    route(req, res, config).catch((error) => {
      res.statusCode = 500;
      res.end(JSON.stringify({ error: error.message }));
    });
  });
}

if (require.main === module) {
  const config = loadConfig(process.env.CONFIG_PATH || "config/default.json");
  createServer(config).listen(config.port, () => {
    console.log(`ledger-export-api listening on port ${config.port}`);
  });
}

module.exports = { createServer };
