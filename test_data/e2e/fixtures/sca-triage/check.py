#!/usr/bin/env python3
"""Acceptance gate for the dependency-scan triage.

Nothing in this file lists a verdict. For every finding in scan/report.json the expected
verdict is derived here from the same evidence the report is about:

  a. package-lock.json - which version of each package npm installs (overrides are already
     applied in the lockfile) and, by following the dependency edges, which copies of a
     package are in the tree;
  b. src/ - the bare package imports of the production code, followed transitively through
     the lockfile's dependency edges with Node's resolution rule (nearest node_modules);
  c. package.json - for the manifest step.

A finding is a true positive when a copy of the package that production code reaches
resolves to a version inside the vulnerable range. It is a false positive otherwise.

Checked, in this order:
  1. report/triage.md has one `finding = <id> | <verdict> | <reason>` line per scan finding;
  2. the verdicts agree with what (a) and (b) imply (the gate does not say which line is
     wrong, only how many);
  3. every true positive that is a direct dependency declares the first fixed version;
  4. no other dependency version and no override changed.

Exit 0 only when every check passes. This file is part of the scenario definition - editing
it is not a fix.

Run: python3 check.py
"""
import json
import os
import re
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
os.chdir(BASE)

REPORT = "report/triage.md"
SCAN = "scan/report.json"
LOCK = "package-lock.json"
MANIFEST = "package.json"
SRC = "src"

LINE_RE = re.compile(
    r"^`?finding`?\s*=\s*`?([A-Za-z0-9._-]+)`?\s*\|\s*`?(true_positive|false_positive)`?\s*\|"
    r"\s*(.*?)\s*$"
)
IMPORT_RE = re.compile(
    r"""(?:\bfrom\s*|\bimport\s*\(?\s*|\brequire\s*\(\s*)["']([^"']+)["']"""
)
SOURCE_SUFFIXES = (".js", ".mjs", ".cjs")

failures = []


def check(name, ok, detail=""):
    print(("PASS" if ok else "FAIL"), "-", name, ("- " + detail if not ok and detail else ""))
    if not ok:
        failures.append(name)


def load_json(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh), None
    except (OSError, ValueError) as exc:
        return None, f"{path} cannot be read as JSON: {exc}"


# --- lockfile and imports --------------------------------------------------------------
def package_name(specifier):
    """Bare package behind an import specifier ('pkg', 'pkg/sub', '@scope/pkg/sub'), or None
    for relative paths, URLs and built-ins."""
    specifier = specifier.strip()
    if specifier.startswith(("node:", ".")) or "/" in specifier[:1] or "://" in specifier:
        return None
    parts = specifier.split("/")
    return "/".join(parts[:2]) if specifier.startswith("@") else parts[0]


def instance_name(location):
    """Package name of a lockfile entry such as node_modules/a/node_modules/@scope/b."""
    return location.rsplit("node_modules/", 1)[-1]


def resolve(packages, from_location, name):
    """Node's lookup rule over the lockfile: the nearest node_modules folder, then upwards."""
    base = from_location
    while True:
        candidate = (base + "/" if base else "") + "node_modules/" + name
        if candidate in packages:
            return candidate
        if not base:
            return None
        base = os.path.dirname(base)


def reachable_from_src(packages):
    """Lockfile locations of every package copy that production code under src/ loads,
    directly or through the dependencies of what it loads."""
    locations, queue = set(), []

    def add(location):
        if location and location not in locations:
            locations.add(location)
            queue.append(location)

    for root, dirs, files in os.walk(SRC):
        dirs.sort()
        for name in sorted(files):
            if not name.endswith(SOURCE_SUFFIXES):
                continue
            with open(os.path.join(root, name), encoding="utf-8") as fh:
                for specifier in IMPORT_RE.findall(fh.read()):
                    wanted = package_name(specifier)
                    if wanted:
                        add(resolve(packages, "", wanted))

    index = 0
    while index < len(queue):
        entry = packages[queue[index]]
        edges = dict(entry.get("dependencies", {}))
        edges.update(entry.get("optionalDependencies", {}))
        for wanted in edges:
            add(resolve(packages, queue[index], wanted))
        index += 1
    return locations


# --- versions -----------------------------------------------------------------------------
def parse_version(text):
    match = re.match(r"^\s*v?(\d+)(?:\.(\d+))?(?:\.(\d+))?", text or "")
    if not match:
        return None
    return tuple(int(part) if part else 0 for part in match.groups())


def bounds(comparator):
    """(lowest_included, lowest_excluded) for one comparator, None for no bound on that side."""
    match = re.match(r"^(>=|<=|>|<|=|\^|~)?\s*v?([0-9xX*]+)(?:\.([0-9xX*]+))?(?:\.([0-9xX*]+))?$",
                     comparator)
    if not match:
        raise ValueError(f"unsupported comparator {comparator!r}")
    operator = match.group(1) or ""
    parts = [None if part is None or part in "xX*" else int(part) for part in match.groups()[1:]]
    if parts[0] is None:
        return None, None
    if parts[1] is None:
        if operator in ("", "^", "~"):
            return (parts[0], 0, 0), (parts[0] + 1, 0, 0)
        parts[1], parts[2] = 0, 0
    elif parts[2] is None:
        if operator in ("", "~", "^"):
            return (parts[0], parts[1], 0), (parts[0], parts[1] + 1, 0)
        parts[2] = 0
    low = tuple(parts)
    if operator == ">":
        return (low[0], low[1], low[2] + 1), None
    if operator == "<":
        return None, low
    if operator == ">=":
        return low, None
    if operator == "<=":
        return None, (low[0], low[1], low[2] + 1)
    if operator == "^":
        top = 1 if parts[0] > 0 else (2 if parts[1] > 0 else 3)
        return low, (low[0] + 1, 0, 0) if top == 1 else (
            (low[0], low[1] + 1, 0) if top == 2 else (low[0], low[1], low[2] + 1))
    if operator == "~":
        return low, (low[0], low[1] + 1, 0)
    return low, (low[0], low[1], low[2] + 1)


def in_range(version, expression):
    version = parse_version(version)
    if version is None:
        return False
    for comparator in expression.replace(",", " ").split():
        if comparator.lower() in ("*", "x"):
            continue
        lowest, highest = bounds(comparator)
        if lowest is not None and version < lowest:
            return False
        if highest is not None and version >= highest:
            return False
    return True


def derive(findings, lock):
    """Verdict per finding id from the lockfile and the production imports."""
    packages = lock["packages"]
    reachable = reachable_from_src(packages)
    copies = {}
    for location, entry in packages.items():
        if "node_modules/" in location:
            copies.setdefault(instance_name(location), []).append((location, entry.get("version")))
    verdicts = {}
    for finding in findings:
        name = finding["package"]
        exposed = [location for location, version in copies.get(name, [])
                   if location in reachable and in_range(version, finding["vulnerable_range"])]
        verdicts[finding["id"]] = "true_positive" if exposed else "false_positive"
    return verdicts


# --- report ----------------------------------------------------------------------------
def read_lines():
    """(entries, problems): entries are (id, verdict, reason, line number)."""
    if not os.path.isfile(REPORT):
        return None, [f"{REPORT} not found"]
    entries, problems = [], []
    with open(REPORT, encoding="utf-8") as fh:
        text = fh.read()
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        line = re.sub(r"^[-*]\s+", "", line)          # list bullet
        if line.startswith("|"):                      # table row
            line = line[1:]
            line = line[:-1] if line.endswith("|") else line
        if not re.match(r"^`?finding\b", line):
            continue
        match = LINE_RE.match(line)
        if not match:
            problems.append(f"line {number} is not in the form "
                            "`finding = <id> | <verdict> | <reason>`")
        else:
            entries.append((match.group(1), match.group(2), match.group(3), number))
    return entries, problems


# --- main ------------------------------------------------------------------------------
def main():
    scan, error = load_json(SCAN)
    if error:
        check("scan/report.json is readable", False, error)
        return 1
    lock, error = load_json(LOCK)
    if error:
        check("package-lock.json is readable", False, error)
        return 1
    manifest, error = load_json(MANIFEST)
    findings = scan["findings"]
    expected = derive(findings, lock)
    by_id = {finding["id"]: finding for finding in findings}

    # 1. the report
    entries, problems = read_lines()
    if entries is None:
        check(f"{REPORT} exists", False, problems[0])
        return 1
    check(f"{REPORT} lines are in the form `finding = <id> | <verdict> | <reason>`", not problems,
          "; ".join(problems[:3]))
    listed = {}
    for finding_id, verdict, reason, _ in entries:
        listed.setdefault(finding_id, []).append((verdict, reason))
    twice = sorted(finding_id for finding_id, seen in listed.items() if len(seen) > 1)
    unknown = sorted(finding_id for finding_id in listed if finding_id not in by_id)
    check("every finding id appears at most once and comes from the scan export",
          not twice and not unknown,
          f"listed more than once: {', '.join(twice) or 'none'}; not in the scan: "
          f"{', '.join(unknown) or 'none'}")
    missing = sum(1 for finding_id in by_id if finding_id not in listed)
    check("every scan finding has a line", missing == 0, f"{missing} finding(s) have no line")
    thin = sorted(finding_id for finding_id, seen in listed.items()
                  for _, reason in seen if len(reason.split()) < 3)
    check("every line gives a reason", not thin, f"reason too short for: {', '.join(thin) or 'none'}")

    # 2. the verdicts
    wrong = sum(1 for finding_id, verdict, _, _ in entries
                if finding_id in expected and verdict != expected[finding_id])
    check("verdicts agree with what the lockfile, the overrides and the imports in src/ imply",
          wrong == 0 and missing == 0 and not twice and not unknown,
          f"{wrong} line(s) are triaged the other way round; the gate does not say which")

    # 3. and 4. the manifest
    root = lock["packages"].get("", {})
    direct = root.get("dependencies", {})
    snapshot = dict(root.get("dependencies", {}))
    snapshot.update(root.get("devDependencies", {}))
    declared = dict(manifest.get("dependencies", {}) if manifest else {})
    upgraded = [finding_id for finding_id, verdict in expected.items()
                if verdict == "true_positive" and by_id[finding_id]["package"] in direct]
    stale, missing_dep = [], []
    for finding_id in upgraded:
        name = by_id[finding_id]["package"]
        fixed = (by_id[finding_id].get("fixed_versions") or [None])[0]
        wanted = parse_version(fixed) if fixed else None
        written = parse_version(re.sub(r"^[\s=v^~]+", "", str(declared.get(name, ""))))
        if name not in declared:
            missing_dep.append(name)
        elif not (wanted and written and wanted == written):
            stale.append(name)
    verdicts_ok = wrong == 0 and missing == 0 and not twice and not unknown
    shown = sorted if verdicts_ok else (lambda names: f"{len(names)} of them")
    check("every true positive that is a direct dependency declares the first fixed version",
          not stale and not missing_dep,
          f"{len(stale)} of {len(upgraded)} still declare another version or a later major "
          f"({', '.join(shown(stale))}); "
          f"{len(missing_dep)} no longer listed in dependencies")

    touched = []
    for name, version in snapshot.items():
        if name in direct and name in {by_id[i]["package"] for i in upgraded}:
            continue
        if manifest is None or manifest.get("dependencies", {}).get(name, manifest.get(
                "devDependencies", {}).get(name)) != version:
            touched.append(name)
    check("dependency versions that are not true-positive upgrades are unchanged",
          not touched, f"{len(touched)} changed ({', '.join(shown(touched))})")
    check("overrides are unchanged", manifest is not None
          and manifest.get("overrides", {}) == root.get("overrides", {}),
          "package.json overrides differ from the lockfile root entry")

    print()
    if failures:
        print(f"check.py: {len(failures)} check(s) FAILED")
        return 1
    print("check.py: all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
