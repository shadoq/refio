# Changelog

## 2.1.0 (2026-11-18)

- Security: the attachment download link (`/download?name=`) no longer accepts names that leave
  the upload directory. Reported through the responsible disclosure address by an external
  researcher.
- The ticket list refreshes itself every 30 seconds.

## 2.0.0 (2026-10-29)

- Security: the agent tools page that printed the server configuration was removed. Operations
  reads the same values from the deployment platform now.
- Security: every JSON request carries the CSRF token from the page.
- Breaking: ES modules only; browsers without module support are out of support.

## 1.9.3 (2026-10-13)

- Last release before the 2.x line.
- Ticket list, ticket page and attachments.
- Ticket export for print.

## 1.9.0 (2026-09-26)

- Initial release with the sign-in form and the ticket list.
