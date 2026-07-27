Create a complete single self-contained file `invoice_pilot_{{MODEL_ID}}_01.html` (HTML + CSS + JavaScript inline;
no frameworks or external libraries, no build step). Build "INVOICE PILOT", a clients + invoices
app as a single-page app with hash routing and localStorage persistence. It must work by opening
the file directly in a browser.

Routes: `#/dashboard`, `#/clients`, `#/client/:id`, `#/invoices`, `#/invoice/new`,
`#/invoice/:id`, `#/settings`.

Features:
- Clients CRUD (name, email, address, tax id, notes).
- An invoice builder with a line-items table (description, qty, unit price, VAT rate, discount) and
  live net / VAT / gross totals.
- Invoice statuses Draft / Sent / Paid / Overdue (overdue computed from the due date).
- An invoice preview with a "Print / Save as PDF" button using window.print and a dedicated
  `@media print` layout that looks clean as a PDF.
- JSON export/import.
- Validation with inline errors, toasts and confirm dialogs before destructive actions.

Seed a little demo data on first load. Deliver the one file `invoice_pilot_{{MODEL_ID}}_01.html`.
