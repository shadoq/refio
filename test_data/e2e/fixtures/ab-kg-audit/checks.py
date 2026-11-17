#!/usr/bin/env python3
"""Acceptance gate: audit/violations.json must list exactly the rule violations
recomputed from graph/entities.triples under docs/rules.md."""
import json
import re
import sys
from collections import defaultdict


def load_graph():
    born, died, same_as, reports_to, parent_of = {}, {}, [], {}, {}
    with open("graph/entities.triples", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            m = re.match(r"^(\S+) (born_year|died_year|same_as|reports_to|parent_of) (\S+)$", line)
            if not m:
                continue
            s, pred, o = m.groups()
            if pred == "born_year":
                born[s] = int(o)
            elif pred == "died_year":
                died[s] = int(o)
            elif pred == "same_as":
                same_as.append((s, o))
            elif pred == "reports_to":
                reports_to[s] = o
            else:
                parent_of.setdefault(s, set()).add(o)
    return born, died, same_as, reports_to, parent_of


def find_cycle_subjects(reports_to):
    """Return one subject per cycle: the alphabetically smallest node on it."""
    color = {}
    on_stack = set()
    stack = []
    subjects = set()

    def visit(node):
        color[node] = 1
        stack.append(node)
        on_stack.add(node)
        nxt = reports_to.get(node)
        if nxt is not None:
            c = color.get(nxt, 0)
            if c == 0:
                visit(nxt)
            elif c == 1 and nxt in on_stack:
                cyc = stack[stack.index(nxt):]
                subjects.add(min(cyc))
        color[node] = 2
        on_stack.discard(node)
        stack.pop()

    for node in reports_to:
        if color.get(node, 0) == 0:
            visit(node)
    return subjects


def recompute():
    born, died, same_as, reports_to, parent_of = load_graph()
    found = set()
    for s in find_cycle_subjects(reports_to):
        found.add(("cycle", s))
    for person, dyear in died.items():
        if person in born and born[person] >= dyear:
            found.add(("born_after_death", person))
    for a, b in same_as:
        if (b, a) not in same_as:
            found.add(("same_as_asymmetric", a))
    for s, t in reports_to.items():
        if t not in born:
            found.add(("missing_manager", t))
    for parent, kids in parent_of.items():
        for kid in kids:
            if parent in born and kid in born and born[parent] >= born[kid]:
                found.add(("parent_not_older", parent))
    return found


def main() -> int:
    expected = recompute()
    if len(expected) != 7:
        print(f"FAIL: internal check - graph derives {len(expected)} violations, "
              f"scenario ships 7 (gate/scenario drift)")
        return 1
    try:
        with open("audit/violations.json", encoding="utf-8") as fh:
            reported = json.load(fh)
    except OSError:
        print("FAIL: audit/violations.json is missing")
        return 1
    except ValueError as exc:
        print(f"FAIL: audit/violations.json does not parse: {exc}")
        return 1
    entries = reported.get("violations") if isinstance(reported, dict) else None
    if not isinstance(entries, list):
        print("FAIL: violations.json must be an object with a 'violations' array")
        return 1
    got = set()
    for e in entries:
        if not isinstance(e, dict) or "rule" not in e or "subject" not in e:
            print(f"FAIL: malformed entry: {e!r} (each entry needs rule + subject)")
            return 1
        got.add((e["rule"], e["subject"]))
    missing = expected - got
    extra = got - expected
    problems = []
    if missing:
        problems.append(f"missing violations: {sorted(missing)}")
    if extra:
        problems.append(f"reported but not real: {sorted(extra)}")
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: exactly the seven real violations, none invented")
    return 0


if __name__ == "__main__":
    sys.exit(main())
