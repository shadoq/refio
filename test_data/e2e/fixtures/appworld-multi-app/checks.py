import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUTPUT = os.path.join(HERE, "out", "briefing.json")

EXPECTED = [
    {
        "title": "Design sync",
        "start": "08:00",
        "attendees": [
            {"name": "Iga Wren", "email": "iga.wren@example.test"},
            {"name": "Zofia Marsh", "email": "zofia.marsh@example.test"},
        ],
        "notes": ["2025-03-10-design-moodboard.md"],
    },
    {
        "title": "Sprint planning",
        "start": "09:30",
        "attendees": [
            {"name": "Dana Kowal", "email": "dana.kowal@example.test"},
            {"name": "Iga Wren", "email": "iga.wren@example.test"},
            {"name": "Marek Lis", "email": "marek.lis@example.test"},
        ],
        "notes": ["2025-03-10-sprint-prep.md", "2025-03-11-sprint-risks.md"],
    },
    {
        "title": "Vendor call",
        "start": "14:00",
        "attendees": [
            {"name": "Dana Kowal", "email": "dana.kowal@example.test"},
            {"name": "Piotr Sand", "email": "piotr.sand@example.test"},
        ],
        "notes": ["2025-03-11-vendor-terms.md"],
    },
    {
        "title": "1:1 Marek",
        "start": "16:30",
        "attendees": [{"name": "Marek Lis", "email": "marek.lis@example.test"}],
        "notes": [],
    },
]


def main():
    if not os.path.isfile(OUTPUT):
        print("missing out/briefing.json")
        return 1
    try:
        with open(OUTPUT, encoding="utf-8") as handle:
            got = json.load(handle)
    except ValueError as err:
        print("out/briefing.json is not valid JSON:", err)
        return 1
    if not isinstance(got, list) or len(got) != len(EXPECTED):
        print("expected %d meetings, got %s" % (len(EXPECTED), len(got) if isinstance(got, list) else type(got).__name__))
        return 1
    bad = 0
    for i, (g, e) in enumerate(zip(got, EXPECTED)):
        if g != e:
            bad += 1
            print("meeting #%d (%s) differs:\n  got      %s\n  expected %s" % (i + 1, e["title"], json.dumps(g), json.dumps(e)))
    if bad:
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
