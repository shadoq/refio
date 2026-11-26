#!/usr/bin/env python3
"""Acceptance gate for the IAM least-privilege review.

It runs the project's own evaluator (src/iam/evaluator.py) against policy/policy.json and
proves four things: every access a normal principal was granted in the logs still works; a
list of privileged requests is denied; the accesses used in the suspicious burst are denied;
and the policy really is least privilege (nobody keeps an action it never used, nobody reaches
resources outside the directories it worked in, unknown principals get nothing). The policy
structure and the report are checked as well. The burst is re-derived from the logs and the
report values are compared by digest: this file holds no answer in clear text. Exit 0 only
when every check passes. This file is part of the scenario definition - editing it is not a fix.

Run: python3 check.py
"""
import sys

sys.dont_write_bytecode = True

import collections
import glob
import hashlib
import importlib.util
import json
import os
import re

BASE = os.path.dirname(os.path.abspath(__file__))
POLICY = os.path.join(BASE, "policy", "policy.json")
REPORT = os.path.join(BASE, "report", "iam-review.md")
EVALUATOR = os.path.join(BASE, "src", "iam", "evaluator.py")
LOG_GLOB = os.path.join(BASE, "logs", "access-2026-10-*.jsonl")

# A directory prefix is accepted for a principal only in a directory where it used at least
# this many different resources (below that, the exact resources have to be listed).
COLLECTION_MIN = 3

# sha256 of the network source of the suspicious burst and of the principal behind it.
BURST_SOURCE_DIGEST = "5a8acdab39fb4124be95706d997e51fa96194722f61e4cd82b0ac3c90db72ded"
FLAGGED_PRINCIPAL_DIGEST = "ce1840e46fbebdc495aec3eaa864af5bad578960e3dd6927d293889f3676879a"

# Privileged requests. Each one must be denied for every principal that was not granted
# exactly this request in the logs (and always for the flagged principal when the burst used it).
PROBES = [
    ("secrets:Read", "secrets/prod/db-password"),
    ("secrets:Read", "secrets/prod/stripe-live-key"),
    ("secrets:Read", "secrets/prod/tls-private-key"),
    ("secrets:Read", "secrets/prod/aws-root-key"),
    ("secrets:Read", "secrets/oncall/pager-token"),
    ("secrets:Read", "secrets/oncall/slack-webhook"),
    ("secrets:Write", "secrets/oncall/pager-token"),
    ("iam:PutPolicy", "iam/policy"),
    ("iam:GetPolicy", "iam/policy"),
    ("iam:DeleteRole", "iam/role-root"),
    ("iam:ListRoles", "iam/roles"),
    ("iam:GetRole", "iam/role-deploy"),
    ("files:Delete", "files/finance/q3-forecast.xlsx"),
    ("files:Delete", "files/finance/expenses-2024.xlsx"),
    ("files:Delete", "files/runbooks/mysql.md"),
    ("files:Write", "files/finance/q3-forecast.xlsx"),
    ("files:Write", "files/runbooks/mysql.md"),
    ("files:Write", "files/eng/api-design.md"),
    ("files:Read", "files/finance/salary-bands.xlsx"),
    ("deploy:Push", "deploy/api"),
    ("deploy:Push", "deploy/worker"),
    ("db:Query", "db/payroll"),
    ("db:Query", "db/customers"),
    ("db:Query", "db/prod-replica"),
    ("db:Query", "db/warehouse"),
    ("db:Query", "db/reporting"),
    ("db:Query", "db/users"),
    ("db:Dump", "db/prod-replica"),
    ("db:Insert", "db/reporting"),
]
# Actions and resources that exist although nobody uses them legitimately: they catch wide
# patterns such as `db:*` or `secrets/*` in the precision checks.
EXTRA_ACTIONS = ["secrets:Write", "iam:DeleteRole", "db:Drop", "deploy:Rollback"]
EXTRA_RESOURCES = ["secrets/prod/aws-root-key", "db/users", "iam/role-root"]
# Names that never appear in the logs must get nothing at all.
STRANGERS = ["root", "admin", "anonymous", "svc-deploy-2", "svc-report-prod", "group:staff", "*", ""]

TIME_WORDS = re.compile(
    r"night|nocturn|\b0[0-5]:[0-5][0-9]|\b0[0-5] ?h\b|out[- ]of[- ]hours|off[- ]hours|"
    r"after midnight|small hours|burst|weekend|unsociable|unusual hour|odd hour", re.I)
ACTION_WORDS = re.compile(
    r"never|not (?:seen|used)|new\b|newly|first time|privileg|escalat|secret|iam:|delete|dump|"
    r"payroll|customer|outside|beyond|scope|unusual action|strange", re.I)
SOURCE_WORDS = re.compile(
    r"source|\bip\b|address|origin|host|network|subnet|from (?:an?|the) |unknown|unseen", re.I)

failures = []


def check(name, ok, detail=""):
    print(("PASS" if ok else "FAIL"), "-", name)
    if not ok:
        print("      " + detail)
        failures.append(name)


def problem(exc):
    return "raised %s: %s" % (type(exc).__name__, exc)


def digest(kind, value):
    return hashlib.sha256(("iam-review-v1|%s|%s" % (kind, value)).encode("utf-8")).hexdigest()


def parent(resource):
    return resource.rsplit("/", 1)[0] if "/" in resource else ""


def key_values(lines, key):
    """Values of the `key = value` lines of a report (one per line, optional list bullet)."""
    pattern = re.compile(r"^\s*(?:[-*]\s+)?`?" + re.escape(key) + r"`?\s*=\s*(.*?)\s*$")
    return [match.group(1).strip("`") for match in map(pattern.match, lines) if match]


def load_evaluator():
    spec = importlib.util.spec_from_file_location("iam_evaluator_gate", EVALUATOR)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class Engine(object):
    """decide() of the project evaluator, with exceptions turned into recorded failures."""

    def __init__(self, evaluator, policy):
        self.evaluator = evaluator
        self.policy = policy
        self.errors = []
        self.calls = 0

    def allows(self, principal, action, resource):
        self.calls += 1
        try:
            return bool(self.evaluator.decide(self.policy, principal, action, resource))
        except Exception as exc:
            self.errors.append("%s %s %s: %s" % (principal, action, resource, problem(exc)))
            return False


SELF_TEST_POLICY = {
    "Statement": [
        {"Sid": "t1", "Effect": "Allow", "Principal": "alice", "Action": ["files:Read"],
         "Resource": ["files/a/*"]},
        {"Sid": "t2", "Effect": "Allow", "Principal": "group:dev", "Action": ["*"],
         "Resource": ["*"]},
        {"Sid": "t3", "Effect": "Deny", "Principal": "alice", "Action": ["files:*"],
         "Resource": ["files/a/secret.txt"]},
    ]
}
SELF_TEST_GROUPS = {"dev": ["bob", "dave"]}
SELF_TEST_CASES = [
    ("alice", "files:Read", "files/a/x.md", True, "exact action and prefix resource"),
    ("alice", "files:Write", "files/a/x.md", False, "action not listed"),
    ("alice", "files:Read", "files/b/x.md", False, "resource outside the prefix"),
    ("alice", "files:Read", "files/a/secret.txt", False, "explicit Deny beats Allow"),
    ("alice", "filesread", "files/a/x.md", False, "action match is case sensitive"),
    ("bob", "deploy:Push", "deploy/api", True, "group principal with wildcards"),
    ("dave", "db:Query", "db/users", True, "group principal with wildcards"),
    ("carol", "deploy:Push", "deploy/api", False, "principal without a statement"),
    ("*", "deploy:Push", "deploy/api", False, "a request for the literal name *"),
    ("", "deploy:Push", "deploy/api", False, "empty principal"),
]


def evaluator_self_test(evaluator):
    wrong = []
    for principal, action, resource, expected, label in SELF_TEST_CASES:
        try:
            got = bool(evaluator.decide(SELF_TEST_POLICY, principal, action, resource,
                                        SELF_TEST_GROUPS))
        except Exception as exc:
            wrong.append("%s (%s)" % (label, problem(exc)))
            continue
        if got is not expected:
            wrong.append(label)
    check("the evaluator behaves as documented (default deny, wildcards, groups, Deny wins)",
          not wrong, "; ".join(wrong))


def read_logs():
    entries, problems = [], []
    paths = sorted(glob.glob(LOG_GLOB))
    if len(paths) != 30:
        problems.append("expected 30 log files, found %d" % len(paths))
    for path in paths:
        name = os.path.basename(path)
        with open(path, "r", encoding="utf-8") as fh:
            for number, line in enumerate(fh, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    item = json.loads(line)
                    record = {"ts": str(item["ts"]), "principal": str(item["principal"]),
                              "action": str(item["action"]), "resource": str(item["resource"]),
                              "result": str(item["result"]), "source": str(item["source"])}
                except Exception as exc:
                    problems.append("%s:%d: %s" % (name, number, problem(exc)))
                    continue
                if record["result"] not in ("allow", "deny"):
                    problems.append("%s:%d: result is %r" % (name, number, record["result"]))
                    continue
                entries.append(record)
    entries.sort(key=lambda entry: entry["ts"])
    check("logs/access-2026-10-DD.jsonl: 30 daily logs read (%d requests)" % len(entries),
          bool(entries) and not problems, "; ".join(problems[:5]))
    return entries


def burst_checks(entries):
    burst = [entry for entry in entries
             if digest("source", entry["source"]) == BURST_SOURCE_DIGEST]
    check("the suspicious burst is found in the logs", bool(burst),
          "no logged request comes from the network source of the burst")
    names = sorted({entry["principal"] for entry in burst})
    check("the burst belongs to exactly one principal", len(names) == 1,
          "the burst carries %d different principals" % len(names))
    flagged = names[0] if len(names) == 1 else None
    if burst:
        print("      %d requests between %s and %s" % (len(burst), burst[0]["ts"], burst[-1]["ts"]))
    return flagged, {(entry["action"], entry["resource"]) for entry in burst}


def statement_checks(document, principals, flagged, granted):
    """Return the normalised statements of the policy, or None when it is unusable."""
    if not isinstance(document, dict) or not isinstance(document.get("Statement"), list):
        check("policy/policy.json is a document with a Statement list", False,
              "got %s" % ("no Statement list" if isinstance(document, dict) else type(document).__name__))
        return None
    statements, problems = [], []
    wildcard_actions, both_wildcards = [], []
    owner_of = collections.Counter()
    for index, raw in enumerate(document["Statement"], 1):
        sid = "statement #%d" % index
        if not isinstance(raw, dict):
            problems.append("%s is not an object" % sid)
            continue
        if isinstance(raw.get("Sid"), str) and raw["Sid"].strip():
            sid = "statement %r" % raw["Sid"]
        effect = raw.get("Effect")
        if effect != "Allow":
            problems.append("%s: Effect is %r, the house rules allow only Allow statements "
                            "(what is not allowed is denied)" % (sid, effect))
        principal = raw.get("Principal")
        if isinstance(principal, list) and len(principal) == 1:
            principal = principal[0]
        if not isinstance(principal, str) or not principal:
            problems.append("%s: Principal must be one exact principal name" % sid)
            principal = None
        elif principal not in principals:
            problems.append("%s: Principal %r is not a principal of the logs (no groups, no "
                            "* and no unknown names)" % (sid, principal))
        else:
            owner_of[principal] += 1
        actions = raw.get("Action") if isinstance(raw.get("Action"), list) else [raw.get("Action")]
        resources = raw.get("Resource") if isinstance(raw.get("Resource"), list) else [raw.get("Resource")]
        if not actions or not all(isinstance(item, str) and item for item in actions):
            problems.append("%s: Action must be a non-empty list of strings" % sid)
            actions = []
        if not resources or not all(isinstance(item, str) and item for item in resources):
            problems.append("%s: Resource must be a non-empty list of strings" % sid)
            resources = []
        for action in actions:
            if action.strip() in ("*", "*:*"):
                wildcard_actions.append("%s: Action %s" % (sid, action))
        if any("*" in item for item in actions) and any("*" in item for item in resources):
            both_wildcards.append(sid)
        statements.append({"Effect": effect, "Principal": principal,
                           "Action": actions, "Resource": resources})
    check("policy/policy.json is a list of well formed Allow statements with exact principals",
          not problems, "; ".join(problems[:6]))
    check("no statement grants everything (no `*` or `*:*` action)", not wildcard_actions,
          "; ".join(wildcard_actions[:6]))
    check("no statement combines a wildcard action with a wildcard resource", not both_wildcards,
          "; ".join(both_wildcards[:6]))
    check("no principal owns more than one statement",
          all(count == 1 for count in owner_of.values()),
          "; ".join("%s owns %d" % (name, count) for name, count in sorted(owner_of.items())
                    if count != 1))
    expected = [name for name in sorted(granted) if name != flagged]
    missing = [name for name in expected if owner_of.get(name, 0) != 1]
    unknown = [name for name in sorted(owner_of) if name not in granted]
    check("exactly one statement per principal that keeps access, and none or one for the "
          "flagged principal (%d or %d statements in total)" % (len(expected), len(expected) + 1),
          not missing and not unknown and owner_of.get(flagged, 0) <= 1,
          "; ".join(["%s has %d statement(s)" % (name, owner_of.get(name, 0)) for name in missing]
                    + ["%s has a statement although the logs show no granted access" % name
                       for name in unknown]))
    if not statements and not problems:
        check("policy/policy.json keeps at least the %d statements a working policy needs"
              % len(expected), False, "the Statement list is empty: nothing is allowed any more")
    return statements


def behaviour_checks(engine, principals, flagged, granted, used_actions, scope, burst_pairs):
    replayed, lost = 0, []
    for principal in sorted(granted):
        if principal == flagged:
            continue
        for action, resource in sorted(granted[principal]):
            replayed += 1
            if not engine.allows(principal, action, resource):
                lost.append("%s: %s %s" % (principal, action, resource))
    check("every access a normal principal was granted in the logs is still allowed "
          "(%d distinct requests replayed)" % replayed, not lost,
          "now denied: " + "; ".join(lost[:8]))

    kept = []
    for action, resource in sorted(burst_pairs):
        if flagged is not None and engine.allows(flagged, action, resource):
            kept.append("%s %s" % (action, resource))
    check("every access used in the suspicious burst is denied to the principal behind it",
          flagged is not None and not kept, "still allowed: " + "; ".join(kept[:8]))

    wide, stranger_hits = [], []
    for principal in principals:
        required = set(PROBES) - granted[principal]
        if principal == flagged:
            required |= burst_pairs
        for action, resource in sorted(required):
            if engine.allows(principal, action, resource):
                wide.append("%s: %s %s" % (principal, action, resource))
    check("privileged probes are denied (%d requests x %d principals)" % (len(PROBES), len(principals)),
          not wide, "still allowed: " + "; ".join(wide[:8]))

    everything = sorted(set(PROBES) | burst_pairs
                        | {pair for pairs in granted.values() for pair in pairs})
    for principal in STRANGERS:
        stranger_hits.extend("%r: %s %s" % (principal, action, resource)
                             for action, resource in everything
                             if engine.allows(principal, action, resource))
    check("principals that do not exist in the logs get nothing", not stranger_hits,
          "; ".join(stranger_hits[:6]))


def precision_checks(engine, principals, flagged, granted, used_actions, scope, actions, resources):
    for principal in principals:
        extra_actions, outside = [], []
        for action in actions:
            allowed = [resource for resource in resources if engine.allows(principal, action, resource)]
            if not allowed:
                continue
            if action not in used_actions[principal]:
                extra_actions.append("%s (%d resources)" % (action, len(allowed)))
                continue
            for resource in allowed:
                if ((action, resource) not in granted[principal]
                        and parent(resource) not in scope[principal]):
                    outside.append("%s %s" % (action, resource))
        check("%s keeps only what it used" % ("the flagged principal" if principal == flagged
                                             else "principal %s" % principal),
              not extra_actions and not outside,
              "; ".join(["actions it never used: " + ", ".join(extra_actions)] if extra_actions else [])
              + ("; " if extra_actions and outside else "")
              + "; ".join(["beyond its own resources and directories: " + ", ".join(outside[:6])]
                          if outside else []))


def report_checks(flagged, statement_count):
    if not os.path.isfile(REPORT):
        check("report/iam-review.md exists", False, "file not found")
        return
    with open(REPORT, "r", encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    check("report/iam-review.md exists", True)

    names = key_values(lines, "flagged_principal")
    check("the report has exactly one `flagged_principal = <name>` line", len(names) == 1,
          "found %d such line(s)" % len(names))
    check("flagged_principal names the principal behind the suspicious burst (re-derived from "
          "the logs)", len(names) == 1
          and digest("principal", names[0].strip().lower()) == FLAGGED_PRINCIPAL_DIGEST,
          "the report names %r" % (names[0] if names else None))

    reasons = key_values(lines, "flagged_reason")
    check("the report has exactly one `flagged_reason = <reason>` line", len(reasons) == 1,
          "found %d such line(s)" % len(reasons))
    text = reasons[0].strip() if reasons else ""
    missing = [label for label, words in (
        ("the time window", TIME_WORDS), ("the actions or resources that were new", ACTION_WORDS),
        ("the source the requests came from", SOURCE_WORDS)) if not words.search(text)]
    check("flagged_reason is one sentence (20 to 400 characters) that mentions the time window, "
          "the new actions or resources and the source",
          20 <= len(text) <= 400 and not missing,
          "; ".join(["%d characters long" % len(text)] + ["does not mention " + item for item in missing]))

    counts = key_values(lines, "statements")
    check("statements = <n> appears once and equals the number of statements in policy.json",
          len(counts) == 1 and counts[0].isdigit() and int(counts[0]) == statement_count,
          "found %r, the policy has %d statement(s)" % (counts, statement_count))


def main():
    evaluator = None
    try:
        evaluator = load_evaluator()
        check("src/iam/evaluator.py loads", True)
    except Exception as exc:
        check("src/iam/evaluator.py loads", False, problem(exc))

    entries = read_logs() if evaluator is not None else []
    flagged, burst_pairs = burst_checks(entries) if entries else (None, set())
    principals = sorted({entry["principal"] for entry in entries})

    granted = collections.defaultdict(set)
    used_actions = collections.defaultdict(set)
    directory_use = collections.defaultdict(set)
    for entry in entries:
        if entry["result"] == "allow":
            principal = entry["principal"]
            granted[principal].add((entry["action"], entry["resource"]))
            used_actions[principal].add(entry["action"])
            directory_use[(principal, parent(entry["resource"]))].add(entry["resource"])
    scope = {principal: {directory for (owner, directory), used in directory_use.items()
                         if owner == principal and len(used) >= COLLECTION_MIN}
             for principal in principals}
    actions = sorted({entry["action"] for entry in entries}
                     | {action for action, _ in PROBES} | set(EXTRA_ACTIONS))
    resources = sorted({entry["resource"] for entry in entries}
                       | {resource for _, resource in PROBES} | set(EXTRA_RESOURCES))

    document = None
    try:
        with open(POLICY, "r", encoding="utf-8") as fh:
            document = json.load(fh)
        check("policy/policy.json is valid JSON", True)
    except Exception as exc:
        check("policy/policy.json is valid JSON", False, problem(exc))

    statements = statement_checks(document, principals, flagged, granted) if document is not None else None
    if evaluator is not None and statements is not None:
        engine = Engine(evaluator, document)
        behaviour_checks(engine, principals, flagged, granted, used_actions, scope, burst_pairs)
        precision_checks(engine, principals, flagged, granted, used_actions, scope, actions, resources)
        check("the evaluator answered every request without an error", not engine.errors,
              "; ".join(engine.errors[:4]))
        print("      %d decisions evaluated" % engine.calls)
    else:
        check("policy/policy.json is checked against the evaluator", False,
              "skipped: the evaluator does not load or the policy is unusable")
    report_checks(flagged, len(statements) if statements is not None else -1)

    print()
    if failures:
        print("check: %d check(s) FAILED: %s" % (len(failures), "; ".join(failures)))
        return 1
    print("check: all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
