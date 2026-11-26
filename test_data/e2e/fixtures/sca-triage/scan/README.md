# Scan export format

`report.json` is written by DepScan (the finance group's dependency scanner) once a week.
Fields of one finding:

| Field | Meaning |
| --- | --- |
| `id` | Finding identifier, used to track the finding in tickets and in triage reports. |
| `package` | Package name as npm spells it. |
| `installed_version` | Version the scanner resolved for the package when it ran. |
| `vulnerable_range` | Version range with the defect, in npm comparator syntax. |
| `advisories` | Advisory identifiers of the security group's database (`VULN-<year>-<number>`). |
| `severity` | Scanner severity. |
| `fixed_versions` | Versions that contain the fix, oldest first; the first one is the first fixed version. |
| `title` | Short description from the advisory. |

The scanner resolves packages from the manifest snapshot it is handed by the CI job, not
from the project directory, so compare its `installed_version` with what the current
`package-lock.json` installs before acting on a finding.
