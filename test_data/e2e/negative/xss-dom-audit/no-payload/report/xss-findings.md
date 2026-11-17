# XSS findings - Knowledge Search

## Summary

`renderResults` in `webapp/app.js` assigns attacker-influenced HTML built by string
concatenation to the `#results` container via `innerHTML`, and the query string comes from
`location.hash`. This is a DOM-based cross-site scripting vulnerability (CWE-79).

## Findings

### Finding 1 - DOM XSS in the results rendering

- Sink: `innerHTML` on the `#results` container, in `renderResults` (`webapp/app.js`).
- Source: `location.hash` (`#q=` parameter), read by `parseQuery()`.
- Severity: High.
- CWE: CWE-79.
- Analysis: with no matching corpus entries the raw query is embedded into the HTML string,
  so markup in the fragment is parsed.
- Remediation: use `textContent` / DOM APIs instead of HTML concatenation.

(No proof-of-concept payload was constructed; the code path is clear enough from reading.)
