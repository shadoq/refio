# Gateway response cache

`src/store.js` is the in-memory key/value cache the API gateway keeps between
requests: entries carry an optional TTL (`null` never expires), reads past the
expiry behave as misses, `sweep()` drops expired entries for real, and `set()`
evicts the oldest key once the configured bound is reached.

The TypeScript migration happens next to it: `src/store.ts` is the typed port
of the same store, while `src/store.js` stays in place as the behavioural
reference implementation.
