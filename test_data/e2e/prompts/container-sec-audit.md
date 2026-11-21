The team is about to ship the service in this project (see `README.md`) to production via
Docker. Before that happens, audit the container setup for security misconfiguration:

- `Dockerfile` - the image build.
- `docker-compose.yml` - the local/production composition.
- `.env.example` - the documented environment contract.

Write your findings to `report/container-findings.md` with this structure:

- A short summary of the overall posture.
- One finding per confirmed misconfiguration. Each finding must contain:
  - a title,
  - the file and line/section it lives in,
  - the severity (Critical, High, Medium or Low),
  - the CWE identifier in the form `CWE-<number>` where one applies,
  - why it matters (the concrete attack or failure path),
  - a one-line remediation.

Report only misconfigurations you can confirm from these files; do not pad the report with
generic container hardening advice that is not tied to this setup. Do not modify the
Dockerfile, the compose file or any other project file - the deliverable is the report only.
