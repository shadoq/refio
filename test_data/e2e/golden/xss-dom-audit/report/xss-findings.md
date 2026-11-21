# XSS findings - Knowledge Search

## Summary

The page renders its search query into the DOM without escaping. The no-results path in
`renderResults` (`webapp/app.js`) builds HTML by string concatenation and assigns it to
`innerHTML`, and the query comes straight from `location.hash`. Anyone who can get a victim
to open a crafted link executes script in the page's origin. The rest of the page (the
`renderCount` status line) uses `textContent` and is safe.

## Findings

### Finding 1 - Reflected DOM XSS via the no-results path

- Sink: `innerHTML` on the `#results` container, in `renderResults` (`webapp/app.js`).
- Source: the `q` parameter of `location.hash`, read by `parseQuery()` (the page documents
  `index.html#q=terms` as the shareable search-link format).
- Severity: High.
- CWE: CWE-79 (Improper Neutralization of Input During Web Page Generation - DOM-based XSS).
- PoC (URL fragment): `#q=<img src=x onerror=alert(document.domain)>` (equivalently
  `#q=<svg onload=alert(document.domain)>`). With no corpus match, the query is embedded
  verbatim into the `innerHTML` assignment and the handler fires.
- Remediation: render the query with `textContent` (build the `<p>`/`<b>` nodes via
  `document.createElement`), or escape it before any HTML concatenation.

## What is not a finding

`renderCount` writes `n + " result(s)"` through `textContent`, which never parses HTML - the
status line cannot be used for injection. The corpus list rendering concatenates only
built-in strings, not user input.
