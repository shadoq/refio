'use strict';

const http = require('node:http');
const config = require('./config');
const { handle } = require('./router');

/** Reads the body of a request and stops the connection once more than `limit` bytes arrived. */
function readBody(request, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let received = 0;
    request.on('data', (chunk) => {
      received += chunk.length;
      if (received > limit) {
        request.destroy();
        reject(new RangeError('request body too large'));
        return;
      }
      chunks.push(chunk);
    });
    request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    request.on('error', reject);
  });
}

function send(response, status, type, body) {
  response.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  response.end(body);
}

async function serve(request, response) {
  const url = new URL(request.url, 'http://localhost');
  try {
    const body = request.method === 'POST' ? await readBody(request, config.maxBodyBytes) : '';
    const result = await handle({ method: request.method, url, body });
    send(response, result.status, result.type, result.body);
  } catch (error) {
    if (error instanceof RangeError) {
      send(response, 413, 'text/plain', 'request body too large\n');
    } else if (Number.isInteger(error.status)) {
      send(response, error.status, 'text/plain', `${error.message}\n`);
    } else {
      console.error('request failed', error);
      send(response, 502, 'text/plain', 'upstream request failed\n');
    }
  }
}

function createApp() {
  return http.createServer(serve);
}

if (require.main === module) {
  const app = createApp();
  app.listen(config.port, config.bindAddress, () => {
    console.log(`image-resizer ${config.version} listening on ${config.bindAddress}:${config.port}`);
  });
}

module.exports = { createApp, serve };
