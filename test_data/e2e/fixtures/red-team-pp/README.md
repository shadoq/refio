# ThemeHub preferences service

The preferences service of the local ThemeHub demo: it stores one account's theme and
feature preferences and answers which features are switched on.

- `server/server.js` - the service (Node core modules only, no npm dependencies, binds
  127.0.0.1).

`GET /` lists the endpoints. A client sends a JSON patch to `PUT /api/settings`, which is
merged into the saved preferences section by section; `GET /api/features` turns a feature on
when `settings.enabled[<name>]` is truthy. Feature flags in the `enabled` section belong to
the staff console, so the preferences API refuses patches to that section. The staff-only
admin panel and the diagnostics endpoint are not part of the demo account. The harness starts the service with
`node server/server.js <port>`.
