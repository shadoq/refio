#!/usr/bin/env python3
"""Acceptance gate: answers/authority.json must match the approval resolution
recomputed from graph/org.triples plus the rules in docs/authority.md."""
import json
import re
import sys
from collections import defaultdict

RANK = {"worker": 0, "lead": 1, "manager": 2, "director": 3}
REQ = {"expense_report": 1, "purchase_order": 2, "security_exception": 3, "budget_transfer": 3}
SELF_OK = {"expense_report", "purchase_order"}

QUESTIONS = [
    ("q1", "elena", "expense_report"),
    ("q2", "dave", "expense_report"),
    ("q3", "elena", "purchase_order"),
    ("q4", "carla", "purchase_order"),
    ("q5", "farid", "security_exception"),
    ("q6", "gita", "budget_transfer"),
    ("q7", "ivan", "budget_transfer"),
    ("q8", "henk", "security_exception"),
    ("q9", "june", "expense_report"),
    ("q10", "bob", "purchase_order"),
]


def load_graph():
    role = {}
    manager = {}
    with open("graph/org.triples", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            m = re.match(r"^(\S+) (reports_to|holds_role) (\S+)$", line)
            if not m:
                continue
            s, pred, o = m.groups()
            if pred == "holds_role":
                role[s] = o
            else:
                manager[s] = o
    return role, manager


def resolve(requester, rtype, role, manager):
    need = REQ[rtype]
    rank = RANK[role[requester]]
    if rank >= need and rtype in SELF_OK:
        return requester
    person = requester
    while person in manager:
        person = manager[person]
        if RANK[role[person]] >= need:
            return person
    return None


def recompute(role, manager):
    out = {}
    for key, requester, rtype in QUESTIONS:
        out[key] = resolve(requester, rtype, role, manager)
    return out


def main() -> int:
    role, manager = load_graph()
    expected = recompute(role, manager)
    if any(v is None for v in expected.values()):
        print("FAIL: internal check - a question does not resolve (scenario drift)")
        return 1
    try:
        with open("answers/authority.json", encoding="utf-8") as fh:
            reported = json.load(fh)
    except OSError:
        print("FAIL: answers/authority.json is missing")
        return 1
    except ValueError as exc:
        print(f"FAIL: answers/authority.json does not parse: {exc}")
        return 1
    problems = []
    for key, want in expected.items():
        got = reported.get(key, "<<missing>>")
        if got != want:
            problems.append(f"{key}: reported {got!r}, recomputed {want!r}")
    extra = set(reported) - {k for k, _, _ in QUESTIONS}
    if extra:
        problems.append(f"unexpected keys: {sorted(extra)}")
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: all ten approval resolutions match the rules and the graph")
    return 0


if __name__ == "__main__":
    sys.exit(main())
