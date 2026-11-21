We need the Stockroom service built. The full contract is in `SPEC.md`: endpoints, status codes, error bodies, ETag handling and pagination rules. Implement `createServer()` in `src/server.js` so it follows the spec exactly, with Node built-ins only.

`npm test` (which runs `node --test tests/api.test.js`) starts the server in-process on an ephemeral port and is the acceptance check. Do not edit the tests or the spec.
