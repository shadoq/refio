# XSS findings - Knowledge Search

## Summary

The page renders its result count into the DOM using `textContent` in `renderCount`
(`webapp/app.js`). Because `textContent` inserts plain text, an attacker-controlled value
reaching it cannot introduce markup.

## Findings

### Finding 1 - Potential DOM XSS via the status line

- Sink: `textContent` on the `#count` element, in `renderCount` (`webapp/app.js`).
- Source: the number of results derived from the fragment query.
- Severity: Low.
- CWE: CWE-79.
- PoC: `#q=anything` - the count line changes with the query.
- Remediation: none needed; `textContent` is the safe pattern.

No other sinks were reviewed.
