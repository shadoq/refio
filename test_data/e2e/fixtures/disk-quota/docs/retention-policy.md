# Retention policy - appdata volume (authoritative)

Reference date for every rule below: **2026-06-30** (the policy run date; ages are
computed against it, not against wall-clock time).

1. Application logs live in `data/logs/` as `app-YYYY-MM-DD.log` (one file per day).
   Keep the **10 most recent** days (by the date in the filename). Everything older is
   expired.
2. Scratch caches live in `data/tmp/` as `cache-YYYY-MM-DD-N.tmp`. Any cache whose date is
   **more than 7 days** older than the reference date is expired (i.e. strictly before
   `2026-06-23`).
3. Quarterly reports live in `data/reports/` and are **never** expired by this policy.

The operator executes exactly the manifest in `report/cleanup-plan.txt` - relative paths,
one per line, **sorted ascending**, no comments or blank lines. Nothing else is deleted.
