# image-resizer

`image-resizer` is the HTTP service behind the product image URLs: it fetches an original from
the CDN layer or from a partner feed, scales it to the requested size and hands the result to
the encoders. It runs on Node 20 with `node:http` only; there are no npm dependencies.

## Layout

| Path | Purpose |
| --- | --- |
| `src/server.js` | the HTTP server, the request body limit, error responses |
| `src/config.js` | settings from the environment |
| `src/router.js` | the routes: resize, signed links, originals |
| `src/store.js` | access to the stored originals |
| `src/options.js` | the presets and how a request may change them |
| `src/source.js` | downloads of originals (CDN layer, partner feeds) |
| `src/validate.js` | the size and format parameters |
| `src/signing.js` | signed feed links and upload tokens |
| `docs/threat-model.md` | the categories the security review tracks |

## Running it locally

```
node src/server.js
```

The service listens on `127.0.0.1:8080` unless `BIND_ADDRESS` and `PORT` say otherwise. The
current release is in `package.json` (`version`); `src/CHANGELOG.md` lists what each release
changed.

The staging deployment runs an older build and is managed by the release team; it is not part of
this checkout.
