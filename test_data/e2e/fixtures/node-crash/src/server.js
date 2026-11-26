import { createServer } from 'node:http';

// Small status API in front of the job worker.
export function createApp(deps) {
  return createServer((req, res) => {
    const send = (code, body) => {
      res.writeHead(code, { 'content-type': 'application/json' });
      res.end(JSON.stringify(body));
    };
    if (req.method === 'GET' && req.url === '/health') return send(200, { ok: true });
    if (req.method === 'GET' && req.url === '/jobs') return send(200, deps.listItems().map((i) => i.id));
    return send(404, { error: 'not found' });
  });
}
