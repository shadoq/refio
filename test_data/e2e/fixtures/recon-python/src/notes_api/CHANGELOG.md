# Changelog

## 2.1.0 (2026-11-18)

- Security: the attachment download now refuses names that leave the storage directory. Reported
  through the responsible disclosure address by an external researcher.
- Search returns in one pass for notebooks of any size.

## 2.0.0 (2026-10-30)

- Security: notebook export calls the converter without a shell.
- Security: the diagnostics page that printed the process environment was removed. Operations
  reads the same values from the deployment platform now.
- New API tokens are 32 random bytes.
- Settings fall back to safe defaults when the environment is incomplete.
- Breaking: `/v1/notes` answers with JSON only.

## 1.9.3 (2026-10-12)

- Last release before the 2.x line.
- Notebook export in PDF, HTML and plain text.
- Full text search over notes.
- Nightly database backups.

## 1.9.0 (2026-09-28)

- Initial release with accounts, notebooks and attachments.
