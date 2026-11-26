import { createServer, IncomingMessage, ServerResponse } from 'node:http';
import { loadConfig, RelayConfig } from './config';
import { publicMessage, RelayError, renderErrorPage } from './errors';
import { handle } from './router';

/** Reads the body of a request and stops the connection once more than `limit` bytes arrived. */
function readBody(request: IncomingMessage, limit: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let received = 0;
    request.on('data', (chunk: Buffer) => {
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

function send(response: ServerResponse, status: number, type: string, body: string): void {
  response.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  response.end(body);
}

async function serve(request: IncomingMessage, response: ServerResponse, config: RelayConfig): Promise<void> {
  const url = new URL(request.url ?? '/', 'http://localhost');
  try {
    const body = request.method === 'POST' ? await readBody(request, config.maxBodyBytes) : '';
    const result = await handle(
      { method: request.method ?? 'GET', url, headers: request.headers, body }, config);
    send(response, result.status, result.type, result.body);
  } catch (error) {
    if (error instanceof RangeError) {
      send(response, 413, 'text/plain', 'request body too large\n');
    } else if (error instanceof RelayError && error.status < 500) {
      send(response, error.status, 'text/plain', `${publicMessage(error)}\n`);
    } else {
      console.error('request failed', error);
      send(response, 500, 'text/html', renderErrorPage(error, config));
    }
  }
}

function main(): void {
  const config = loadConfig();
  createServer((request, response) => {
    void serve(request, response, config);
  }).listen(config.port, config.bindAddress, () => {
    console.log(`webhook-relay ${config.version} listening on ${config.bindAddress}:${config.port}`);
  });
}

main();
