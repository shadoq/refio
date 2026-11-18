Implement the client for our items service in `client.js`. The service contract is documented in `API.md`: pagination with cursors, retry rules for 429 and 5xx, error handling. Follow the document exactly, including which calls may be retried.

`node --test test/client.test.js` starts a fake service in-process and checks the client against it, using an injected fake `sleep` so no real waiting happens. It must pass. Do not edit the tests or `API.md`.
