# Changelog

## 2.1.0 (2026-11-20)

- Security: the archive download (`/archive?name=`) accepts plain stored names only, so a name
  can no longer leave the archive directory. Reported through the responsible disclosure address
  by an external researcher.
- A failed delivery is retried twice before the on-call engineer is paged.

## 2.0.0 (2026-11-02)

- Security: the diagnostics page that printed the process environment was removed. Operations
  reads the same values from the deployment platform now.
- Security: the documented signature scheme is compared in constant time.
- Breaking: compiled with TypeScript in strict mode; Node 20 only.

## 1.9.3 (2026-10-15)

- Last release before the 2.x line.
- Events in the partner platform's format.
- Chat post and page for failed deliveries.

## 1.9.0 (2026-10-01)

- Initial release with the events route and the subscriber registry.
