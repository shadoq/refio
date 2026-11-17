## Profile cache storms upstream on concurrent renders

### Symptom

The dashboard renders the profile header, the activity feed and the permissions
panel at the same time, and all three call `cache.get()` for the same key while
the entry is still cold. Upstream now sees two or three identical requests per
profile (their rate limiter started 429ing yesterday), and each caller gets its
own value object, so panels can disagree about the same profile within a single
render. The nightly `warm()` batch has the same problem with duplicate keys in
its list.

### How to reproduce

    node -e '
    const { RequestCache } = require("./src/cache");
    let calls = 0;
    const cache = new RequestCache(async (k) => { calls += 1; return { id: k }; });
    await Promise.all([cache.get("p7"), cache.get("p7"), cache.get("p7")]);
    console.log(calls); // 3 - should be 1
    '

### Expected behaviour

Concurrent `get()` calls for a cold key must share a single underlying load and
observe the same value object; a rejected load must reject every joined caller
without caching the failure, so the next attempt retries; entries must still
expire after `ttlMs`; and `warm()` must coalesce duplicate keys through the same
path. The public API (`get`, `warm`, `peek`, `drop`, `stats`) stays as it is.

### Your task

1. Reproduce the double fetch in `src/cache.js` and identify why concurrent
   misses each start their own loader call instead of joining the first one.
2. Fix `src/cache.js` in place: coalesce concurrent loads per key (the
   `pending` map is the natural place for the in-flight promises) and make
   `warm()` go through the same path, keeping TTL expiry and the exports
   unchanged.
3. Run the gate: `node checks.js` - it replays the concurrent-render storm, the
   warmup batch, the rejection path and TTL expiry, and must pass.

Do not modify `checks.js` or the README: the fix belongs in `src/cache.js`.
