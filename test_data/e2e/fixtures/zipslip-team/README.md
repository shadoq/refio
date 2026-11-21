# Upload extractor - audit workspace

- `src/extractor.py` - the archive extractor for user uploads.
- `samples/evil.zip` - a hostile sample (its entry names matter).
- `samples/legit.zip` - a clean sample.
- `canary/` - must stay untouched by any extraction.
- `.refio/agents/` - the audit-patch team profiles.
- `verify_attack.py` - the acceptance gate.
