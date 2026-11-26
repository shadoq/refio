// headers-app: switched off by the security review until the hardening is approved.
// Every request is refused; nothing is served.
"use strict";

const http = require("node:http");

const PORT = Number(process.env.PORT || 3000);

const server = http.createServer((req, res) => {
  res.writeHead(403, { "Content-Type": "text/plain; charset=utf-8" });
  res.end("Forbidden: the portal is switched off until the security review is closed.");
});

server.listen(PORT, () => {
  console.log("listening " + server.address().port);
});
