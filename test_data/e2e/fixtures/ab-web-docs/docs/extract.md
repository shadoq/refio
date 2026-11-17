# Widget Hub - extraction run

A local docs site is served at `http://127.0.0.1:8742`:

- `/releases.html` - release table (version, date, status; statuses: released,
  deprecated, unreleased),
- `/status.html` - CI build status per service,
- `/team.html` - team roster (some teams have a "lead:" marker, some do not).

Your task: extract the facts into `facts/facts.json` with exactly these keys:

- `latest_released_version` - the version with the newest date among **released** rows
  (unreleased builds never count, no matter how new their date),
- `latest_release_date` - that version's date,
- `deprecated_versions` - all versions with status deprecated, sorted ascending,
- `build_status` - object mapping each service to its build status,
- `team_leads` - object mapping each team **that has a lead** to the lead's name.

`python3 checks.py` must exit 0 (it re-parses the pages with the stdlib HTML parser and
compares). Do not modify the pages, the server or the gate.
