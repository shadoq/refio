# job-worker

Node 20 service that keeps the order dashboard's job data fresh. A background
worker refreshes every tracked job from the catalog on a timer and stores the
result; a small HTTP surface exposes health and the tracked job ids.

## Layout

- `src/config.js` - runtime settings (refresh interval, retry policy)
- `src/jobs.js` - the background scheduler
- `src/refresh.js` - one refresh round over all jobs
- `src/retry.js` - retry helper with exponential backoff
- `src/server.js` - the HTTP status API
- `check.js` - offline checks (no network, no catalog needed)

## Running

    node check.js

`check.js` drives the worker with a stubbed catalog and store, so it runs
anywhere without network access. All dependencies are injected, there are no
npm packages.
