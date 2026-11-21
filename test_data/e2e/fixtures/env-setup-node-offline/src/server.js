'use strict';
const http = require('node:http');
const { readConfig } = require('./config');

const config = readConfig();

const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', app: config.name, greeting: config.greeting, port: config.port }));
    return;
  }
  res.writeHead(404);
  res.end();
});

server.listen(config.port, '127.0.0.1', () => {
  console.log('listening on ' + config.port);
});
