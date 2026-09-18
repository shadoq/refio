This project is a directory, not a single file. Read `README.md` first: it is the complete specification of
an HTTP API for notes, and `test/api.test.js` is the acceptance suite that will be run against your work.

Implement the API so that `node --test` passes from the project root. The suite starts `src/server.js` as a
child process with the port in the `PORT` environment variable and then drives the API over HTTP, so the
server must listen on that port and answer `/health` as soon as it is up.

Use only Node's standard library: no dependencies, no install step, no framework. The data lives in memory
for the lifetime of the process.

Split the work across several modules instead of one long file: `src/server.js` is the entry point that
owns the socket and nothing else, and at least a storage module and a routing or rules module sit behind
it, each required from `src/server.js` or from one another with a relative path.

Do not modify `test/api.test.js` or `README.md`. Everything the suite checks is described in the README:
the status codes, the response shapes, the validation rules and field names, the paging and sorting, the
`ETag` header with `If-None-Match` and `If-Match`, and the error envelopes. Read it carefully rather than
guessing, and run the suite yourself until it passes. Deliver the working server rooted at `src/server.js`.
