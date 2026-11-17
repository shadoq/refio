A small docs site is running at {{FIXTURE_SERVER}} with three pages: `/releases.html`
(version/date/status table), `/status.html` (CI build status per service) and
`/team.html` (team roster, some teams have a "lead:" marker). The exact extraction rules
and the deliverable shape are fixed in `docs/extract.md` - note the traps: an
**unreleased** build must never win "latest released", and teams without a lead must be
left out.

Browse the pages over HTTP and write the extracted facts to `facts/facts.json` with
exactly the five documented keys. `python3 checks.py` must exit 0 - it re-parses the
served pages and compares. Use HTTP requests for the site; do not modify the pages, the
server or the gate.
