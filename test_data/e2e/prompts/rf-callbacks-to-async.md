`src/downloader.js` in this fixture is the legacy asset downloader: five steps
wired together with error-first callbacks, each nested inside the previous
one, with the outer completion handler passed down through the whole pyramid.
It works, but every new step deepens the nesting and the error paths are
checked five times over.

Your task:

1. Rewrite `src/downloader.js` so every step (`getUser`, `getPermissions`,
   `fetchAsset`, `transform`, `save`) is an async function and the pipeline
   awaits them in sequence, with the awaited chain wrapped in try/catch.
2. Change the public entry point to `downloadPipeline(url, opts)` returning a
   Promise that resolves with the saved record or rejects with the first
   failure. No callback parameters may remain anywhere in the file.
3. Preserve the behaviour exactly: the same resolved values (including the
   `opts.compact` and `opts.prefix` handling and the `SAVED` side effect), the
   same exports, and byte-for-byte identical error messages on every failure
   path (`unknown user: ...`, `asset not found: ...`, `permission denied: ...`,
   `transform failed: ...`, `save failed: ...`, `malformed url: ...`).
4. Run the acceptance gate with `node checks.js` and iterate until it passes -
   it is written to accept both the current calling convention and the
   Promise-based one, so it must stay green before and after the rewrite.

Do not modify `checks.js` or the README: the rewrite belongs in
`src/downloader.js`.
