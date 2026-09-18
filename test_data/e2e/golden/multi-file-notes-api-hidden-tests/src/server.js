// The HTTP entry point: it reads the request, hands it to the router and writes the
// answer back. The note rules live in ./router.js, the storage in ./store.js.
const http = require("node:http");
const { route } = require("./router");

const PORT = Number(process.env.PORT || 3000);

function readBody(req) {
  return new Promise((resolve, reject) => {
    let text = "";
    req.on("data", (chunk) => (text += chunk));
    req.on("end", () => resolve(text));
    req.on("error", reject);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host ?? "localhost"}`);
  const raw = await readBody(req);

  let payload = null;
  let jsonError = false;
  if (raw.trim() !== "") {
    try {
      payload = JSON.parse(raw);
    } catch {
      jsonError = true;
    }
  }

  const answer = route({
    method: req.method,
    pathname: url.pathname,
    query: url.searchParams,
    headers: req.headers,
    payload,
    jsonError,
  });

  const headers = { ...(answer.headers ?? {}) };
  if (answer.body === null || answer.body === undefined) {
    res.writeHead(answer.status, headers);
    res.end();
    return;
  }
  const text = JSON.stringify(answer.body);
  headers["content-type"] = "application/json";
  headers["content-length"] = Buffer.byteLength(text);
  res.writeHead(answer.status, headers);
  res.end(text);
});

server.listen(PORT);
