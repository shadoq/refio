The API gateway's response cache lives in `src/store.js`: an untyped,
prototype-style key/value store with per-key TTL, JSDoc comments instead of
types, and no build step. The team is moving the gateway to TypeScript and
this module is next in line.

Your task:

1. Create `src/store.ts` as a full TypeScript port of the store: an `Entry`
   interface for the stored records, a `StoreOptions` interface for the
   constructor options, and a `Store` class with strict-mode-clean signatures
   - no `any` anywhere in the file.
2. Use generics where they earn their keep: callers should be able to pin the
   cached value type per store instance.
3. Mirror the runtime behaviour of `src/store.js` exactly: the same TTL
   semantics (`null` never expires, reads past the expiry behave as misses),
   the same insertion order in `keys()`, the same oldest-first eviction in
   `set()`, and the same return values for every method.
4. Leave `src/store.js` untouched - it stays as the behavioural reference for
   the port.

Do not modify `src/store.js` or the README: the new code belongs in
`src/store.ts`.
