You are reviewing the small search page shipped in `webapp/` (see `README.md` in the project
root) before it goes into production. The page is fully static and client-side.

Audit it for DOM-based cross-site scripting and write your findings to
`report/xss-findings.md` with this structure:

- A one-paragraph summary.
- One finding per confirmed vulnerability, containing:
  - a title,
  - the exact sink (DOM API and the function/file it lives in),
  - the source that reaches it,
  - the severity (Critical, High, Medium or Low),
  - the CWE identifier in the form `CWE-<number>`,
  - a working proof-of-concept payload expressed as a URL fragment (the `#...` part),
  - a one-line remediation.

Only report what you can confirm by reading the code; a nearby safe pattern is not a
vulnerability. Do not modify the web app or any other project file - the deliverable is the
report only.
