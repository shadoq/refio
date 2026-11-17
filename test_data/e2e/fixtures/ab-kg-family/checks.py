#!/usr/bin/env python3
"""Acceptance gate: answers/kinship.json must match the kinship recomputed from
graph/family.triples (parent_of / spouse_of, standard definitions)."""
import json
import re
import sys
from collections import defaultdict


def load_graph():
    parents = defaultdict(set)
    children = defaultdict(set)
    spouses = defaultdict(set)
    with open("graph/family.triples", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            m = re.match(r"^(\S+) (parent_of|spouse_of) (\S+)$", line)
            if not m:
                continue
            s, pred, o = m.groups()
            if pred == "parent_of":
                parents[o].add(s)
                children[s].add(o)
            else:
                spouses[s].add(o)
    return parents, children, spouses


def siblings_of(x, parents, children):
    sib = set()
    for p in parents[x]:
        sib |= children[p]
    sib.discard(x)
    return sib


def aunts_uncles_of(x, parents, children):
    au = set()
    for p in parents[x]:
        au |= siblings_of(p, parents, children)
    au -= parents[x]
    au.discard(x)
    return au


def cousins_of(x, parents, children):
    cous = set()
    for au in aunts_uncles_of(x, parents, children):
        cous |= children[au]
    cous -= parents[x]
    cous.discard(x)
    return cous


def grandparents_of(x, parents):
    gp = set()
    for p in parents[x]:
        gp |= parents[p]
    return gp


def recompute(parents, children, spouses):
    out = {
        "q1_parents_of_harry": sorted(parents["harry"]),
        "q2_grandparents_of_lara": sorted(grandparents_of("lara", parents)),
        "q3_siblings_of_irene": sorted(siblings_of("irene", parents, children)),
        "q4_siblings_of_gina": sorted(siblings_of("gina", parents, children)),
        "q5_aunts_uncles_of_mike": sorted(aunts_uncles_of("mike", parents, children)),
        "q6_cousins_of_nina": sorted(cousins_of("nina", parents, children)),
        "q7_cousins_of_lara": sorted(cousins_of("lara", parents, children)),
        "q8_spouse_of_dan": sorted(spouses["dan"])[0],
        "q9_children_of_alfred": sorted(children["alfred"]),
        "q10_grandparents_of_oscar": sorted(grandparents_of("oscar", parents)),
    }
    return out


def main() -> int:
    parents, children, spouses = load_graph()
    expected = recompute(parents, children, spouses)
    try:
        with open("answers/kinship.json", encoding="utf-8") as fh:
            reported = json.load(fh)
    except OSError:
        print("FAIL: answers/kinship.json is missing")
        return 1
    except ValueError as exc:
        print(f"FAIL: answers/kinship.json does not parse: {exc}")
        return 1
    problems = []
    for key, want in expected.items():
        got = reported.get(key, "<<missing>>")
        if got != want:
            problems.append(f"{key}: reported {got!r}, recomputed {want!r}")
    extra = set(reported) - set(expected)
    if extra:
        problems.append(f"unexpected keys: {sorted(extra)}")
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: all ten kinship answers match the graph")
    return 0


if __name__ == "__main__":
    sys.exit(main())
