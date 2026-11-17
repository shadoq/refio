# Asset downloader

`src/downloader.js` exposes `downloadPipeline`, the five-step asset download
used by the nightly batch tooling:

1. `getUser` - resolve the user id embedded in the `dl://<userId>/<assetId>` url.
2. `getPermissions` - fetch the user's permission list; `asset:read` is required.
3. `fetchAsset` - look the asset up in the in-memory catalog.
4. `transform` - decode the asset (`identity`, `gzip`); `opts.compact` halves
   the payload, unsupported encodings fail.
5. `save` - write the record under the `opts.prefix` and enforce the size quota.

`SAVED` holds the written records keyed by file name. `checks.js` is the
acceptance gate: `node checks.js`.
