You are performing a white-box security audit of the small Notes service in this project (see
`README.md` in the project root). The service is internal, but it is reachable from the office
network, so the audit must assume a curious or malicious insider as the attacker.

Audit the application code in `src/` and write a findings report to `report/findings.md` with the
following structure:

- A short summary of the overall security posture.
- One finding per confirmed vulnerability. Each finding must contain:
  - a title,
  - the affected function or location (function name and file),
  - the severity, using one of: Critical, High, Medium, Low,
  - the matching CWE identifier in the form `CWE-<number>`,
  - a short description of the attack path (how an attacker would abuse it),
  - a one-line remediation.

Report every distinct vulnerability you can confirm from the code; do not pad the report with
generic hardening advice that is not tied to a concrete weakness in this codebase.

Do not modify the application code, the database module, or any other project file - the
deliverable is the report only.
