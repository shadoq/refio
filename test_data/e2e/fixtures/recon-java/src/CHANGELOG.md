# Changelog

## 2.1.0 (2026-11-17)

- Security: the attachment download now refuses names that leave the attachment store. Reported
  through the responsible disclosure address by an external researcher.
- Late payment fees are rounded per invoice, not per line.
- Fix: a search with an empty pattern returns the most recent invoices.

## 2.0.0 (2026-10-28)

- Security: the operations status page that printed the process environment was removed.
  Operations reads the same values from the deployment platform now.
- Security: new session tokens are 32 random bytes.
- Settings fall back to safe defaults when the environment is incomplete.
- Breaking: `/invoices` answers with HTML only; the JSON variant moved to `/api/invoices`.

## 1.9.3 (2026-10-11)

- Last release before the 2.x line.
- Import of supplier invoices from XML documents.
- Full text search over invoices.
- Nightly database backups.

## 1.9.0 (2026-09-27)

- Initial release with customers, invoices and attachments.
