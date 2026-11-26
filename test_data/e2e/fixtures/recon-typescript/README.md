# webhook-relay

`webhook-relay` receives signed webhook events from the shop platform and from the partner
platform, keeps every accepted delivery and forwards it to the endpoints subscribed to the
event's topic. It is TypeScript 5 on Node 20 with `node:http` only: no runtime dependencies and
no framework.

## Layout

| Path | Purpose |
| --- | --- |
| `src/server.ts` | the HTTP server, the request body limit, error responses |
| `src/config.ts` | settings from the environment |
| `src/router.ts` | the routes: events, partner events, archive |
| `src/signatures.ts` | verification of the two signature schemes |
| `src/spool.ts` | where deliveries are stored and read back |
| `src/deliver.ts` | the subscriber registry and the forwarding |
| `src/alerts.ts` | chat post and page for failed deliveries |
| `src/errors.ts` | `RelayError` and the error responses |
| `docs/threat-model.md` | the categories the security review tracks |

## Running it locally

```
tsc -p .
RELAY_TENANT_SECRET=local RELAY_SIGNING_TOKEN=local node dist/server.js
```

Compiling needs the TypeScript 5 compiler (`tsc`) and the Node type declarations on the
machine. The relay listens on `127.0.0.1:8080` unless `BIND_ADDRESS` and `PORT` say otherwise.
The current release is in `package.json` (`version`); `src/CHANGELOG.md` lists what each
release changed.

The staging deployment runs an older build and is managed by the release team; it is not part of
this checkout.
