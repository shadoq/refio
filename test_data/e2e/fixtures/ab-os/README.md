# Shared drive

This directory is the file server of a small engineering team.

- `work/` is the shared drive itself: `projects/` (one folder per project,
  each holding `notes.md` and/or `data.csv`), `invoices/2026/` (one folder
  per month with the invoice PDFs), `shared/` (company-wide files) and
  `archive/` for snapshots of material that is being phased out.
- `evaluate.py` is the acceptance gate for the quarterly tidy-up; it must
  exit 0 once the tidy-up is done.
- The company is migrating off the old `acme-corp.example` domain; the
  new one is `acme.example`.
