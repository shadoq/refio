# Changelog

## 2.1.0 (2026-11-19)

- Security: the original download (`/originals?name=`) accepts plain file names only, so a name
  can no longer leave the upload directory. Reported through the responsible disclosure address
  by an external researcher.
- Upload sessions carry a random token.

## 2.0.0 (2026-10-31)

- Security: the diagnostics page that printed the process environment was removed. Operations
  reads the same values from the deployment platform now.
- Security: downloads from the CDN layer are restricted to listed origins and public addresses.
- Breaking: Node 20 only; the `/v1` prefix of every route was dropped.

## 1.9.3 (2026-10-14)

- Last release before the 2.x line.
- Presets `thumbnail` and `preview`, signed feed links.

## 1.9.0 (2026-09-30)

- Initial release with the resize route and stored originals.
