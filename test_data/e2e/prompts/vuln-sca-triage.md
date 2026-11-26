You own `ledger-export-api`, a small Node.js service (see `README.md`). The weekly
dependency scan raised six findings (`scan/report.json`; the format is described in
`scan/README.md`). The scan did not run against the current project state, so not every
finding is real. Triage the export, write the triage report and prepare the manifest change.

1. Decide for every finding whether it is a **true positive** or a **false positive** under
   the team's triage policy (`README.md`): a finding is a true positive when the version
   that `package-lock.json` installs for the production build is inside the vulnerable
   range **and** the service's own code under `src/` loads the package, directly or
   through the dependencies of a package it loads. A finding is a false positive when
   either half fails. Read the lockfile, `package.json` and the imports under `src/`,
   `test/` and `tools/` before deciding; take the installed version from the lockfile,
   not from the scan export or from the version ranges in `package.json`.

2. Write `report/triage.md`. Every finding gets exactly one line in exactly this form,
   with the finding id from the scan export and a short reason that names the evidence:

   ```
   finding = <id> | true_positive | <short reason>
   finding = <id> | false_positive | <short reason>
   ```

   List each finding once, in the order of the scan export, and leave none out. Around
   the lines, summarise which packages you bumped and why the dismissed findings are not
   exploitable in production.

3. For every true positive that is listed under `"dependencies"` in `package.json`, set
   its version to the first fixed version of the finding (the first entry of
   `fixed_versions`), written as `^<version>` or as the bare version. Change nothing else
   in `package.json`: the other dependency versions and the `overrides` block stay as
   they are. CI runs `npm install` to refresh `package-lock.json` and re-runs the
   scanner on the pull request, so do not edit `package-lock.json` and do not run
   `npm install`.

`check.py` is the acceptance gate. It parses the lockfile, follows the imports under
`src/` and the dependency edges, derives the verdict of every finding itself and compares
it with your report (it says how many lines disagree, not which), then checks the versions
in `package.json`. Run it with `python3 check.py` and iterate until it passes. npm is not
needed.

Do not modify `check.py`, `package-lock.json`, `scan/`, `README.md`, `CHANGELOG.md`,
anything under `src/`, `test/` or `tools/`, or `config/`.
