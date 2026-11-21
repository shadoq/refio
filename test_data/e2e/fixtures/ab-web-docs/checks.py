#!/usr/bin/env python3
"""Acceptance gate: facts/facts.json must match the facts re-parsed from the
www/*.html pages (stdlib html.parser - the gate trusts only the served HTML)."""
import json
import sys
from html.parser import HTMLParser


class TableParser(HTMLParser):
    """Extract rows (list of cell texts) from every <table> on one page."""

    def __init__(self):
        super().__init__()
        self.rows = []
        self._row = None
        self._cell = None

    def handle_starttag(self, tag, attrs):
        if tag == "tr":
            self._row = []
        elif tag in ("td", "th") and self._row is not None:
            self._cell = []

    def handle_data(self, data):
        if self._cell is not None:
            self._cell.append(data)

    def handle_endtag(self, tag):
        if tag in ("td", "th") and self._cell is not None:
            self._row.append("".join(self._cell).strip())
            self._cell = None
        elif tag == "tr" and self._row is not None:
            if self._row:
                self.rows.append(self._row)
            self._row = None


class ListParser(HTMLParser):
    """Extract the text of every <li> on one page."""

    def __init__(self):
        super().__init__()
        self.items = []
        self._li = None

    def handle_starttag(self, tag, attrs):
        if tag == "li":
            self._li = []

    def handle_data(self, data):
        if self._li is not None:
            self._li.append(data)

    def handle_endtag(self, tag):
        if tag == "li" and self._li is not None:
            self.items.append("".join(self._li).strip())
            self._li = None


def read_rows(page):
    p = TableParser()
    p.feed(open(f"www/{page}", encoding="utf-8").read())
    return p.rows


def recompute():
    releases = []
    for row in read_rows("releases.html"):
        if row and row[0].lower() == "version":
            continue
        if len(row) == 3:
            releases.append({"version": row[0], "date": row[1], "status": row[2]})
    shipped = [r for r in releases if r["status"] == "released"]
    latest = max(shipped, key=lambda r: r["date"])
    deprecated = sorted(r["version"] for r in releases if r["status"] == "deprecated")

    status_rows = read_rows("status.html")
    build = {}
    for row in status_rows:
        if len(row) == 3 and row[0] != "service":
            build[row[0]] = row[2]

    lp = ListParser()
    lp.feed(open("www/team.html", encoding="utf-8").read())
    leads = {}
    for item in lp.items:
        if "lead:" in item:
            team = item.split("team", 1)[1].split("-")[0].strip()
            lead = item.split("lead:", 1)[1].split("(")[0].strip()
            leads[team] = lead

    return {
        "latest_released_version": latest["version"],
        "latest_release_date": latest["date"],
        "deprecated_versions": deprecated,
        "build_status": build,
        "team_leads": leads,
    }


def main() -> int:
    expected = recompute()
    try:
        with open("facts/facts.json", encoding="utf-8") as fh:
            reported = json.load(fh)
    except OSError:
        print("FAIL: facts/facts.json is missing")
        return 1
    except ValueError as exc:
        print(f"FAIL: facts/facts.json does not parse: {exc}")
        return 1
    problems = []
    for key, want in expected.items():
        got = reported.get(key, "<<missing>>")
        if got != want:
            problems.append(f"{key}: reported {got!r}, pages imply {want!r}")
    extra = set(reported) - set(expected)
    if extra:
        problems.append(f"unexpected keys: {sorted(extra)}")
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: extracted facts match the pages")
    return 0


if __name__ == "__main__":
    sys.exit(main())
